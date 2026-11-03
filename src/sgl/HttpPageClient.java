package sgl;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Bounded HTTPS reads of supported public retailer pages; no login or browser automation. */
final class HttpPageClient {
  private static final Set<String> HOSTS =
      Set.of("mfront.homeplus.co.kr", "www.costco.co.kr", "emart.ssg.com");

  String get(URI uri) throws IOException {
    for (int redirect = 0; redirect < 4; redirect++) {
      if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())
          || uri.getUserInfo() != null || uri.getPort() != -1 && uri.getPort() != 443)
        throw new IOException("지원하지 않는 수집 주소");
      HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setConnectTimeout(5000);
      connection.setReadTimeout(8000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "SmartGroceryLedger/0.6 (public product prices)");
      connection.setRequestProperty("Accept", "text/html");
      connection.setUseCaches(false);
      try {
        int status = connection.getResponseCode();
        if (status >= 300 && status < 400) {
          String location = connection.getHeaderField("Location");
          if (location == null) throw new IOException("이동 주소 없음");
          uri = uri.resolve(location);
          continue;
        }
        if (status != 200) throw new IOException(
            status == 403 ? "사이트 접근 거부 (HTTP 403)"
            : status == 429 ? "사이트 요청 제한 (HTTP 429)" : "HTTP " + status);
        String type = connection.getContentType();
        if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("text/html"))
          throw new IOException("상품 HTML 응답이 아닙니다.");
        if (connection.getContentLengthLong() > 5_000_000)
          throw new IOException("응답 크기 제한 5MB");
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[8192];
          long deadline = System.nanoTime() + 15_000_000_000L;
          for (int length; (length = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("수집 취소");
            if (System.nanoTime() > deadline) throw new IOException("응답 시간 초과");
            if (output.size() + length > 5_000_000) throw new IOException("응답 크기 제한 5MB");
            output.write(buffer, 0, length);
          }
          return output.toString(StandardCharsets.UTF_8);
        }
      } finally {
        connection.disconnect();
      }
    }
    throw new IOException("이동 횟수 초과");
  }
}

