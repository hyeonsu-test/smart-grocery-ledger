package sgl;

import static sgl.Data.*;

import java.awt.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.zip.*;
import javax.swing.*;

/** Executable JAR entry point. Run with --demo for isolated fictional data. */
public final class Main {
  /** Application version. */
  public static final String VERSION = "0.7.2-java-beta";

  private static FileChannel lockChannel;
  private static FileLock processLock;

  private Main() {}

  /**
   * Starts the Swing UI or a headless smoke test.
   *
   * @param args --demo, --data-dir PATH, --no-auto, --smoke-test or --version
   * @throws Exception for fatal setup failures
   */
  public static void main(String[] args) throws Exception {
    boolean demo = false, auto = true;
    Path dir = Path.of(System.getProperty("user.home"), "SmartGroceryLedger", "data");
    for (int i = 0; i < args.length; i++)
      switch (args[i]) {
        case "--demo" -> demo = true;
        case "--no-auto" -> auto = false;
        case "--data-dir" -> {
          if (++i == args.length) throw new IllegalArgumentException("--data-dir requires a path");
          dir = Path.of(args[i]);
        }
        case "--version" -> {
          System.out.println(
              "Smart Grocery Ledger " + VERSION + " | Java " + System.getProperty("java.version"));
          return;
        }
        case "--smoke-test" -> {
          smoke();
          return;
        }
        case "--help" -> {
          System.out.println(
              "java -jar SmartGroceryLedger.jar [--demo] [--data-dir PATH] [--no-auto]"
                  + " [--smoke-test] [--version]");
          return;
        }
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    Files.createDirectories(dir);
    Path file = dir.resolve(demo ? "demo.json" : "ledger.json");
    lockChannel =
        FileChannel.open(
            file.resolveSibling(file.getFileName() + ".lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    processLock = lockChannel.tryLock();
    if (processLock == null) {
      lockChannel.close();
      System.err.println("이 데이터 파일은 다른 앱에서 사용 중입니다.");
      if (!GraphicsEnvironment.isHeadless())
        JOptionPane.showMessageDialog(null, "같은 데이터 파일을 사용하는 앱이 이미 실행 중입니다.");
      System.exit(2);
      return;
    }
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    if (processLock != null) processLock.release();
                    if (lockChannel != null) lockChannel.close();
                  } catch (IOException ignored) {
                  }
                }));
    Repository repo = new Repository(file);
    Clock clock = Clock.systemDefaultZone();
    if (demo && !Files.exists(file) && repo.readError().isEmpty()) {
      var r = repo.saveAtomic(DemoData.create(clock));
      if (!s(r, "status").equals("OK")) throw new IOException(s(r, "message"));
    }
    if (GraphicsEnvironment.isHeadless())
      throw new IllegalStateException(
          "GUI display required; use --smoke-test for headless verification.");
    boolean autoRefresh = auto;
    SwingUtilities.invokeLater(
        () -> {
          SwingAppearance.install();
          LedgerFrame frame = new LedgerFrame(repo, clock, autoRefresh);
          frame.setVisible(true);
        });
  }

  static void openManual(Path dir) throws IOException {
    Path destination = dir.resolve("api-" + VERSION).toAbsolutePath().normalize();
    Files.createDirectories(destination);
    try (InputStream raw = Main.class.getResourceAsStream("/api.zip")) {
      if (raw == null) throw new IOException("Javadoc 자원이 없습니다. build 스크립트로 다시 빌드하세요.");
      try (ZipInputStream zip = new ZipInputStream(raw)) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          Path file = destination.resolve(entry.getName()).normalize();
          if (!file.startsWith(destination)) throw new IOException("Invalid embedded path");
          if (entry.isDirectory()) Files.createDirectories(file);
          else {
            Files.createDirectories(file.getParent());
            Files.copy(zip, file, StandardCopyOption.REPLACE_EXISTING);
          }
        }
      }
    }
    if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
      throw new IOException("브라우저로 " + destination.resolve("index.html") + " 파일을 여세요.");
    Desktop.getDesktop().browse(destination.resolve("index.html").toUri());
  }

  private static void smoke() throws Exception {
    Path dir = Files.createTempDirectory("sgl-smoke-");
    Path file = dir.resolve("ledger.json");
    Clock c = Clock.systemDefaultZone();
    Repository repo = new Repository(file);
    check(repo.saveAtomic(DemoData.create(c)));
    String month = java.time.LocalDate.now(c).toString().substring(0, 7);
    check(
        new LedgerService(repo, c)
            .manageLedger(
                m(
                    "action",
                    "CREATE",
                    "date",
                    java.time.LocalDate.now(c).toString(),
                    "kind",
                    "지출",
                    "category",
                    "식료품",
                    "amount",
                    1500)));
    check(new BudgetService(repo, c).manageBudgetPlan(m("action", "GET", "month", month)));
    check(
        new ProductService(repo, new MartAdapter(), null, c).manageProducts(m("action", "QUERY")));
    check(new PriceService(repo, c).analyzePrices(m("mode", "COMPARE", "product_id", "milk")));
    check(
        new TargetService(repo, c)
            .managePurchaseTargets(
                m("action", "VIEW", "product_ids", new ArrayList<>(java.util.List.of("milk")))));
    if (new Repository(file).snapshot().records().size() != 6)
      throw new AssertionError("restart persistence");
    if (Main.class.getResource("/api.zip") == null)
      throw new AssertionError("embedded Javadoc missing");
    System.out.println("PASS executable JAR: 5 services, write/reload, embedded Javadoc");
    System.out.println(
        "OS="
            + System.getProperty("os.name")
            + " "
            + System.getProperty("os.arch")
            + " Java="
            + System.getProperty("java.version"));
    Files.delete(file);
    Files.delete(dir);
  }

  private static void check(Map<String, Object> r) {
    if (!s(r, "status").equals("OK")) throw new AssertionError(Json.stringify(r));
  }
}
