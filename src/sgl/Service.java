package sgl;

import static sgl.Data.*;

import java.time.*;
import java.util.Map;
import java.util.function.Supplier;

/** Serializes complete service transactions on the shared repository. */
abstract class Service {
  final Repository repo;
  final Clock clock;

  Service(Repository repo, Clock clock) {
    this.repo = repo;
    this.clock = clock;
  }

  OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  Map<String, Object> call(Map<String, Object> request, Supplier<Map<String, Object>> body) {
    synchronized (repo) {
      if (!repo.readError().isEmpty())
        return m("status", "READ_FAILED", "message", repo.readError());
      try {
        return body.get();
      } catch (IllegalArgumentException | java.time.DateTimeException | ArithmeticException e) {
        return m("status", "INVALID_INPUT", "message", e.getMessage(), "draft", request);
      }
    }
  }
}
