package sgl;

import static sgl.Data.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

/**
 * Reads validated UTF-8 JSON and commits by atomic replacement; corrupt files are never
 * overwritten.
 */
public class Repository {
  private final Path path;
  private AppState state = new AppState();
  private String readError = "";

  /**
   * Opens a ledger, retaining any read error for safe read-only failure.
   *
   * @param path data file, independent of the process working directory
   */
  public Repository(Path path) {
    this.path = path.toAbsolutePath().normalize();
    loadState();
  }

  /**
   * Reads and validates existing data.
   *
   * @return status OK or READ_FAILED, plus error message when applicable
   */
  public final synchronized Map<String, Object> loadState() {
    try {
      if (Files.exists(path)) {
        if (Files.size(path) > 50_000_000) throw new IOException("데이터 파일 제한은 50MB입니다.");
        AppState loaded =
            new AppState(obj(Json.parse(Files.readString(path, StandardCharsets.UTF_8))));
        loaded.validate();
        state = loaded;
      } else state = new AppState();
      readError = "";
      return ok();
    } catch (IOException | RuntimeException e) {
      readError = e.getMessage();
      return m("status", "READ_FAILED", "message", readError);
    }
  }

  /**
   * Commits only after validation, flush and atomic file replacement succeed.
   *
   * @param candidate prospective complete state
   * @return OK or SAVE_FAILED; failure preserves current memory and existing file
   */
  public synchronized Map<String, Object> saveAtomic(AppState candidate) {
    if (!readError.isEmpty())
      return m("status", "SAVE_FAILED", "message", "읽기 오류가 있는 파일은 덮어쓰지 않습니다.");
    Path temp = null;
    try {
      candidate.validate();
      Files.createDirectories(path.getParent());
      temp = Files.createTempFile(path.getParent(), "sgl-", ".tmp");
      byte[] bytes = Json.stringify(candidate.toMap()).getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 50_000_000) throw new IOException("데이터 파일 제한은 50MB입니다.");
      try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        while (b.hasRemaining()) out.write(b);
        out.force(true);
      }
      replaceFile(temp, path);
      state = candidate.copy();
      return ok();
    } catch (IOException | RuntimeException e) {
      return m("status", "SAVE_FAILED", "message", e.getMessage());
    } finally {
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          /* Preserve original exception. */
        }
    }
  }

  /**
   * Atomic commit seam used by failure-injection tests.
   *
   * @param temporary fully written candidate
   * @param destination current data file
   * @throws IOException if atomic replacement is unsupported or fails
   */
  protected void replaceFile(Path temporary, Path destination) throws IOException {
    Files.move(
        temporary,
        destination,
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  /**
   * Returns isolated state for service requests.
   *
   * @return deep snapshot
   */
  public synchronized AppState snapshot() {
    return state.copy();
  }

  /**
   * Returns a read error, or the empty string.
   *
   * @return latest load error
   */
  public synchronized String readError() {
    return readError;
  }

  /**
   * Returns the resolved persistence path.
   *
   * @return absolute file path
   */
  public Path path() {
    return path;
  }
}
