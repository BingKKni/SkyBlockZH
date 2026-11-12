package io.github.bingkkni.skyzh.update;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Real loopback HTTP CONNECT, TLS proxy and authenticated SOCKS5; never reaches the public network. */
public final class ProxyHarness {
	private static int passed;

	public static int run(Path root) throws Exception {
		SSLContext previous = SSLContext.getDefault();
		SSLContext tls = tls(root);
		SSLContext.setDefault(tls);
		byte[] data = new byte[24 * 1024]; new java.util.Random(19).nextBytes(data);
		Path original = root.resolve("proxy-original"); Files.write(original, data);
		AtomicInteger directRequests = new AtomicInteger();
		AtomicInteger resumeRequests = new AtomicInteger();
		AtomicInteger resumes = new AtomicInteger();
		AtomicInteger cancellations = new AtomicInteger();
		var firstRead = new java.util.concurrent.CountDownLatch(1);
		HttpServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		((HttpsServer) server).setHttpsConfigurator(new HttpsConfigurator(tls));
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/metadata", exchange -> {
			directRequests.incrementAndGet();
			byte[] json = "{\"route\":\"target\"}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, 0); // chunked
			try (var output = exchange.getResponseBody()) { output.write(json); } finally { exchange.close(); }
		});
		server.createContext("/file", exchange -> {
			directRequests.incrementAndGet();
			String range = exchange.getRequestHeaders().getFirst("Range");
			int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
			if (start != 0) exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
			exchange.sendResponseHeaders(start == 0 ? 200 : 206, data.length - start);
			try (var output = exchange.getResponseBody()) { output.write(data, start, data.length - start); } finally { exchange.close(); }
		});
		server.createContext("/resume", exchange -> {
			int call = resumeRequests.incrementAndGet();
			String range = exchange.getRequestHeaders().getFirst("Range");
			int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
			if (start != 0) {
				resumes.incrementAndGet();
				exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + (data.length - 1) + "/" + data.length);
			}
			exchange.sendResponseHeaders(start == 0 ? 200 : 206, data.length - start);
			try (var output = exchange.getResponseBody()) { output.write(data, start, call == 1 ? 4096 : data.length - start); output.flush(); }
			catch (IOException ignored) {} finally { exchange.close(); }
		});
		server.createContext("/cancel", exchange -> {
			cancellations.incrementAndGet();
			exchange.sendResponseHeaders(200, data.length);
			try (var output = exchange.getResponseBody()) {
				output.write(data, 0, 4096); output.flush();
				if (!firstRead.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("first read timeout");
				output.write(data, 4096, data.length - 4096);
			} catch (IOException ignored) {} catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			finally { exchange.close(); }
		});
		server.createContext("/outside", exchange -> {
			exchange.getResponseHeaders().add("Location", "https://127.0.0.1:" + server.getAddress().getPort() + "/metadata");
			exchange.sendResponseHeaders(302, -1); exchange.close();
		});
		server.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/metadata"); exchange.sendResponseHeaders(302, -1); exchange.close();
		});
		server.start();
		URI base = URI.create("https://localhost:" + server.getAddress().getPort());
		try {
			for (String protocol : java.util.List.of("http", "https", "socks5")) {
				for (boolean auth : java.util.List.of(false, true)) {
					try (Proxy proxy = new Proxy(protocol, auth, tls, 0)) {
						var selected = new UpdateProxy(protocol + "://localhost:" + proxy.port(), auth ? "AccountABC" : "", auth ? "PasswordXYZ" : "");
						UpdateHttp http = http(selected);
						int before = directRequests.get();
						check(protocol + " 代理读取分块 JSON，验证=" + auth, http.json(base.resolve("/metadata")).get("route").getAsString().equals("target"));
						check(protocol + " 重定向仍通过代理", http.json(base.resolve("/redirect")).get("route").getAsString().equals("target") && proxy.connections.get() == 3);
						Path file = root.resolve("proxy-" + protocol + auth + ".bin");
						http.download(new UpdateHttp.Download(base.resolve("/file"), data.length, UpdateFiles.sha256(original)), file, () -> true);
						check(protocol + " 下载通过代理并校验摘要", Arrays.equals(Files.readAllBytes(file), data) && proxy.connections.get() == 4);
						check(protocol + " 验证只发送给代理，大小写不变", proxy.error.get() == null);
						check(protocol + " 代理成功不额外发起直连请求", directRequests.get() - before == 3);
					}
				}
			}
			try (Proxy proxy = new Proxy("http", false, tls, 0)) {
				var selected = new UpdateProxy("http://localhost:" + proxy.port(), "", "");
				UpdateHttp http = http(selected);
				int before = directRequests.get();
				try { http.json(base.resolve("/outside")); throw new AssertionError("redirect accepted"); }
				catch (IOException e) { check("代理和直连均拒绝未允许的重定向", directRequests.get() == before && proxy.connections.get() == 1); }
				URI wrongName = URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/metadata");
				try { ProxyTransport.send(java.net.http.HttpRequest.newBuilder(wrongName).GET().build(), selected, 1000).body().close(); throw new AssertionError("hostname accepted"); }
				catch (javax.net.ssl.SSLException expected) { check("代理隧道内仍拒绝目标证书主机名不匹配", directRequests.get() == before); }
				SSLContext untrusted = SSLContext.getInstance("TLS"); untrusted.init(null, null, null);
				SSLContext.setDefault(untrusted);
				try {
					try { ProxyTransport.send(java.net.http.HttpRequest.newBuilder(base.resolve("/metadata")).GET().build(), selected, 1000).body().close(); throw new AssertionError("certificate accepted"); }
					catch (javax.net.ssl.SSLException expected) { check("代理隧道内仍拒绝不受信任证书", directRequests.get() == before); }
				} finally { SSLContext.setDefault(tls); }
				Path file = root.resolve("cancel-mid-proxy");
				AtomicInteger checks = new AtomicInteger(); int connections = proxy.connections.get();
				try {
					http.download(new UpdateHttp.Download(base.resolve("/cancel"), data.length, UpdateFiles.sha256(original)), file, () -> {
						int call = checks.incrementAndGet(); if (call == 2) firstRead.countDown(); return call < 3;
					});
					throw new AssertionError("cancellation ignored");
				} catch (IOException expected) {
					check("代理传输中途取消不直连且保留部分文件", cancellations.get() == 1 && proxy.connections.get() == connections + 1
						&& !Files.exists(file) && Files.size(file.resolveSibling(file.getFileName() + ".part")) == 4096);
				}
			}
			try (Proxy proxy = new Proxy("https", false, tls, 0)) {
				UpdateHttp http = http(new UpdateProxy("https://127.0.0.1:" + proxy.port(), "", ""));
				int before = directRequests.get();
				check("HTTPS 代理证书主机名不匹配会直连回退", http.json(base.resolve("/metadata")).has("route")
					&& directRequests.get() == before + 1 && proxy.connections.get() == 1);
			}
			check("更新跳转限定站点", UpdateHttp.githubRedirect(URI.create("https://skyblock.xbdqaq.com/skyzh-updates/update.json")));
			for (String invalid : java.util.List.of("https://example.org/a", "https://release-assets.githubusercontent.com:8443/a",
				"http://release-assets.githubusercontent.com/a", "https://user@release-assets.githubusercontent.com/a",
				"https://release-assets.githubusercontent.com.evil.test/a"))
				check("拒绝非可信 CDN 跳转 " + invalid, !UpdateHttp.githubRedirect(URI.create(invalid)));
			for (int failure : java.util.List.of(1, 2, 3)) {
				try (Proxy proxy = new Proxy("http", false, tls, failure)) {
					UpdateHttp http = http(new UpdateProxy("http://localhost:" + proxy.port(), "", ""));
					int before = directRequests.get();
					check("代理失败静默直连元数据 " + failure, http.json(base.resolve("/metadata")).has("route") && directRequests.get() == before + 1);
					Path file = root.resolve("fallback-" + failure);
					http.download(new UpdateHttp.Download(base.resolve("/file"), data.length, UpdateFiles.sha256(original)), file, () -> true);
					check("代理失败静默直连下载 " + failure, Arrays.equals(Files.readAllBytes(file), data) && proxy.connections.get() == 2);
				}
			}
			try (Proxy proxy = new Proxy("socks5", true, tls, 0)) {
				UpdateHttp http = http(new UpdateProxy("socks5://localhost:" + proxy.port(), "WRONG", "WRONG"));
				check("SOCKS 验证失败仍直连重试", http.json(base.resolve("/metadata")).has("route"));
			}
			// A valid tunnel followed by a broken response must retry the entire operation, not just connect().
			try (Proxy proxy = new Proxy("http", false, tls, 4)) {
				UpdateHttp http = http(new UpdateProxy("http://localhost:" + proxy.port(), "", ""));
				Path file = root.resolve("fallback-interrupted");
				http.download(new UpdateHttp.Download(base.resolve("/file"), data.length, UpdateFiles.sha256(original)), file, () -> true);
				check("代理隧道中断后直连重试", Arrays.equals(Files.readAllBytes(file), data));
			}
			try (Proxy proxy = new Proxy("http", false, tls, 0)) {
				UpdateHttp http = http(new UpdateProxy("http://localhost:" + proxy.port(), "", ""));
				Path file = root.resolve("fallback-resume");
				http.download(new UpdateHttp.Download(base.resolve("/resume"), data.length, UpdateFiles.sha256(original)), file, () -> true);
				check("代理响应中途截断后直连续传且仅重试一次", Arrays.equals(Files.readAllBytes(file), data)
					&& resumeRequests.get() == 2 && resumes.get() == 1 && proxy.connections.get() == 1);
			}
			try (Proxy proxy = new Proxy("http", false, tls, 1)) {
				UpdateHttp http = http(new UpdateProxy("http://localhost:" + proxy.port(), "", ""));
				try { http.json(base.resolve("/missing")); throw new AssertionError("expected failure"); }
				catch (IOException e) { check("两条线路失败沿用正常 HTTP 错误", e.getMessage().equals("更新站点 HTTP 404")); }
				try { http.download(new UpdateHttp.Download(base.resolve("/file"), data.length, UpdateFiles.sha256(original)), root.resolve("cancel-proxy"), () -> false); }
				catch (IOException e) { check("取消下载不尝试代理或直连", proxy.connections.get() == 1); }
			}
		} finally { server.stop(0); SSLContext.setDefault(previous); }
		return passed;
	}

	private static UpdateHttp http(UpdateProxy proxy) {
		return new UpdateHttp(HttpClient.newBuilder().proxy(java.net.ProxySelector.of(null)).build(),
			uri -> uri.getHost().equals("localhost"), 1000, () -> proxy);
	}

	private static SSLContext tls(Path root) throws Exception {
		Path store = root.resolve("proxy-test.p12");
		Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
			"-genkeypair", "-alias", "loopback", "-keyalg", "RSA", "-keystore", store.toString(), "-storepass", "test-password",
			"-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-noprompt")
			.redirectErrorStream(true).redirectOutput(root.resolve("proxy-keytool.log").toFile()).start();
		if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("keytool timeout"); }
		if (process.exitValue() != 0) throw new IOException("keytool failed");
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (var input = Files.newInputStream(store)) { keys.load(input, "test-password".toCharArray()); }
		var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, "test-password".toCharArray());
		var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(keys);
		SSLContext tls = SSLContext.getInstance("TLS"); tls.init(km.getKeyManagers(), tm.getTrustManagers(), null); return tls;
	}

	private static final class Proxy implements AutoCloseable {
		final ServerSocket server;
		final AtomicInteger connections = new AtomicInteger();
		final AtomicReference<Throwable> error = new AtomicReference<>();
		final String protocol;
		final boolean auth;
		final int failure;
		volatile boolean closed;
		Proxy(String protocol, boolean auth, SSLContext tls, int failure) throws IOException {
			this.protocol = protocol; this.auth = auth; this.failure = failure;
			server = protocol.equals("https") ? tls.getServerSocketFactory().createServerSocket() : new ServerSocket();
			server.bind(new InetSocketAddress("127.0.0.1", 0));
			Thread.startVirtualThread(() -> {
				while (!closed) {
					try { Socket socket = server.accept(); connections.incrementAndGet(); Thread.startVirtualThread(() -> handle(socket)); }
					catch (IOException e) { if (!closed) error.set(e); }
				}
			});
		}
		int port() { return server.getLocalPort(); }
		private void handle(Socket socket) {
			try (socket) {
				socket.setSoTimeout(5000);
				InputStream input = socket.getInputStream(); var output = socket.getOutputStream();
				if (failure == 1) return;
				if (failure == 2) { output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
				if (failure == 3) { output.write("not HTTP\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); return; }
				String host; int port;
				if (protocol.equals("socks5")) {
					if (input.read() != 5) throw new IOException("version");
					byte[] methods = input.readNBytes(input.read());
					if (auth && methods[methods.length - 1] != 2) throw new IOException("auth not offered");
					output.write(new byte[]{5, (byte) (auth ? 2 : 0)}); output.flush();
					if (auth) {
						if (input.read() != 1) throw new IOException("auth version");
						String account = new String(input.readNBytes(input.read()), StandardCharsets.UTF_8);
						String password = new String(input.readNBytes(input.read()), StandardCharsets.UTF_8);
						boolean valid = account.equals("AccountABC") && password.equals("PasswordXYZ");
						output.write(new byte[]{1, (byte) (valid ? 0 : 1)}); output.flush(); if (!valid) return;
					}
					if (!Arrays.equals(input.readNBytes(4), new byte[]{5, 1, 0, 3})) throw new IOException("SOCKS connect");
					host = new String(input.readNBytes(input.read()), StandardCharsets.US_ASCII); port = input.read() * 256 + input.read();
					output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, 0, 0}); output.flush();
				} else {
					String first = line(input); URI address = URI.create("http://" + first.split(" ")[1]);
					host = address.getHost(); port = address.getPort();
					String authorization = "";
					for (String header; !(header = line(input)).isEmpty();) if (header.startsWith("Proxy-Authorization: ")) authorization = header.substring(21);
					String expected = auth ? "Basic " + Base64.getEncoder().encodeToString("AccountABC:PasswordXYZ".getBytes(StandardCharsets.UTF_8)) : "";
					if (!authorization.equals(expected)) throw new IOException("HTTP auth mismatch");
					output.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush();
				}
				if (failure == 4) return;
				try (Socket target = new Socket(host, port)) {
					Thread copy = Thread.startVirtualThread(() -> { try { input.transferTo(target.getOutputStream()); target.shutdownOutput(); } catch (IOException ignored) {} });
					try { target.getInputStream().transferTo(output); output.flush(); }
					catch (IOException ignored) { /* A TLS peer may close immediately after the complete response. */ }
					finally { copy.interrupt(); }
				}
			} catch (Throwable e) { if (!closed) error.compareAndSet(null, e); }
		}
		@Override public void close() throws IOException { closed = true; server.close(); }
	}

	private static String line(InputStream input) throws IOException {
		StringBuilder value = new StringBuilder();
		for (int next; (next = input.read()) != '\n';) {
			if (next < 0 || value.length() > 8192) throw new IOException("invalid line");
			if (next != '\r') value.append((char) next);
		}
		return value.toString();
	}
	private static void check(String label, boolean success) {
		if (!success) throw new AssertionError(label);
		passed++; System.out.println("  ✓ " + label);
	}
}
