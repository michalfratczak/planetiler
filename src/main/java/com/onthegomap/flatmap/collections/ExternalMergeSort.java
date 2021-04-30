package com.onthegomap.flatmap.collections;

import com.onthegomap.flatmap.monitoring.ProcessInfo;
import com.onthegomap.flatmap.monitoring.ProgressLoggers;
import com.onthegomap.flatmap.monitoring.Stats;
import com.onthegomap.flatmap.worker.Topology;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.apache.commons.io.FileUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ExternalMergeSort implements FeatureSort {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureSort.class);

  private static final long MAX_CHUNK_SIZE = 1_000_000_000; // 1GB

  private final Path dir;
  private final Stats stats;
  private final int chunkSizeLimit;
  private final int workers;

  private final List<Chunk> chunks = new ArrayList<>();
  private Chunk current;
  private volatile boolean sorted = false;

  ExternalMergeSort(Path tempDir, int threads, Stats stats) {
    this(
      tempDir,
      threads,
      (int) Math.min(
        MAX_CHUNK_SIZE,
        (ProcessInfo.getMaxMemoryBytes() / 2) / threads
      ),
      stats
    );
  }

  ExternalMergeSort(Path dir, int workers, int chunkSizeLimit, Stats stats) {
    this.dir = dir;
    this.stats = stats;
    this.chunkSizeLimit = chunkSizeLimit;
    long memory = ProcessInfo.getMaxMemoryBytes();
    if (chunkSizeLimit > memory / 2) {
      throw new IllegalStateException(
        "Not enough memory to use chunk size " + chunkSizeLimit + " only have " + memory);
    }
    this.workers = workers;
    LOGGER.info("Using merge sort feature map, chunk size=" + (chunkSizeLimit / 1_000_000) + "mb workers=" + workers);
    try {
      FileUtils.deleteDirectory(dir.toFile());
      Files.createDirectories(dir);
      newChunk();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void add(Entry item) {
    try {
      assert !sorted;
      current.add(item);
      if (current.bytesInMemory > chunkSizeLimit) {
        newChunk();
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public long getStorageSize() {
    return FileUtils.sizeOfDirectory(dir.toFile());
  }

  private static <T> T time(AtomicLong timer, Supplier<T> func) {
    long start = System.nanoTime();
    try {
      return func.get();
    } finally {
      timer.addAndGet(System.nanoTime() - start);
    }
  }

  @Override
  public void sort() {
    assert !sorted;
    if (current != null) {
      try {
        current.close();
      } catch (IOException e) {
        // ok
      }
    }
    long start = System.nanoTime();
    AtomicLong reading = new AtomicLong(0);
    AtomicLong writing = new AtomicLong(0);
    AtomicLong sorting = new AtomicLong(0);
    AtomicLong doneCounter = new AtomicLong(0);
    CompletableFuture<ProgressLoggers> logger = new CompletableFuture<>();

    var topology = Topology.start("sort", stats)
      .readFromTiny("item_queue", chunks)
      .sinkToConsumer("worker", workers, chunk -> {
        var toSort = time(reading, chunk::readAll);
        time(sorting, toSort::sort);
        time(writing, toSort::flush);
        doneCounter.incrementAndGet();
        try {
          logger.get().log();
        } catch (InterruptedException | ExecutionException e) {
          throw new IllegalStateException(e);
        }
      });

    ProgressLoggers loggers = new ProgressLoggers("sort")
      .addPercentCounter("chunks", chunks.size(), doneCounter)
      .addFileSize(this::getStorageSize)
      .addProcessStats()
      .addTopologyStats(topology);
    logger.complete(loggers);

    topology.await();

    sorted = true;
    LOGGER.info("Sorted all chunks " + Duration.ofNanos(System.nanoTime() - start).toSeconds() +
      "s read:" + Duration.ofNanos(reading.get()).toSeconds() +
      "s write:" + Duration.ofNanos(writing.get()).toSeconds() +
      "s sort:" + Duration.ofNanos(sorting.get()).toSeconds() + "s");
  }

  @NotNull
  @Override
  public Iterator<Entry> iterator() {
    assert sorted;
    PriorityQueue<PeekableScanner> queue = new PriorityQueue<>(chunks.size());
    for (Chunk chunk : chunks) {
      if (chunk.itemCount > 0) {
        queue.add(chunk.newReader());
      }
    }

    return new Iterator<>() {
      @Override
      public boolean hasNext() {
        return !queue.isEmpty();
      }

      @Override
      public Entry next() {
        PeekableScanner scanner = queue.poll();
        assert scanner != null;
        Entry next = scanner.next();
        if (scanner.hasNext()) {
          queue.add(scanner);
        }
        return next;
      }
    };
  }

  private void newChunk() throws IOException {
    Path chunkPath = dir.resolve("chunk" + (chunks.size() + 1));
    chunkPath.toFile().deleteOnExit();
    if (current != null) {
      current.close();
    }
    chunks.add(current = new Chunk(chunkPath));
  }

  class Chunk implements Closeable {

    private final Path path;
    private final DataOutputStream outputStream;
    private int bytesInMemory = 0;
    private int itemCount = 0;

    private Chunk(Path path) throws IOException {
      this.path = path;
      this.outputStream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), 50_000));
    }

    public PeekableScanner newReader() {
      return new PeekableScanner(path, itemCount);
    }

    public void add(Entry entry) throws IOException {
      write(outputStream, entry);
      bytesInMemory +=
        // pointer to feature
        8 +
          // Feature class overhead
          16 +
          // long sort member of feature
          8 +
          // byte array pointer
          8 +
          // byte array size
          24 + entry.value().length;
      itemCount++;
    }

    public class SortableChunk {

      private Entry[] featuresToSort;

      private SortableChunk(Entry[] featuresToSort) {
        this.featuresToSort = featuresToSort;
      }

      public SortableChunk sort() {
        Arrays.sort(featuresToSort);
        return this;
      }

      public SortableChunk flush() {
        try (DataOutputStream out = new DataOutputStream(
          new BufferedOutputStream(Files.newOutputStream(path), 50_000))) {
          for (Entry feature : featuresToSort) {
            write(out, feature);
          }
          featuresToSort = null;
          return this;
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    public SortableChunk readAll() {
      try (PeekableScanner scanner = newReader()) {
        Entry[] featuresToSort = new Entry[itemCount];
        int i = 0;
        while (scanner.hasNext()) {
          featuresToSort[i] = scanner.next();
          i++;
        }
        if (i != itemCount) {
          throw new IllegalStateException("Expected " + itemCount + " features in " + path + " got " + i);
        }
        return new SortableChunk(featuresToSort);
      }
    }

    public static void write(DataOutputStream out, Entry entry) throws IOException {
      out.writeLong(entry.sortKey());
      out.writeInt(entry.value().length);
      out.write(entry.value());
    }

    @Override
    public void close() throws IOException {
      outputStream.close();
    }
  }

  class PeekableScanner implements Closeable, Comparable<PeekableScanner>, Iterator<Entry> {

    private final int count;
    private int read = 0;
    private final DataInputStream input;
    private Entry next;

    PeekableScanner(Path path, int count) {
      this.count = count;
      try {
        input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 50_000));
        next = readNextFeature();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public Entry next() {
      Entry current = next;
      if ((next = readNextFeature()) == null) {
        close();
      }
      return current;
    }

    private Entry readNextFeature() {
      if (read < count) {
        try {
          long nextSort = input.readLong();
          int length = input.readInt();
          byte[] bytes = input.readNBytes(length);
          read++;
          return new Entry(nextSort, bytes);
        } catch (IOException e) {
          throw new IllegalStateException(e);
        }
      } else {
        return null;
      }
    }

    @Override
    public void close() {
      try {
        input.close();
      } catch (IOException e) {
        LOGGER.warn("Error closing chunk", e);
      }
    }

    @Override
    public int compareTo(@NotNull PeekableScanner o) {
      return next.compareTo(o.next);
    }
  }
}
