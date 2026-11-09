package io.github.bingkkni.skyzh.update;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.IDN;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.TreeMap;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Per-request proxy tunnels. No JVM-wide authenticator, proxy properties or TLS overrides. */
final class ProxyTransport {
	private ProxyTransport() {}

	static UpdateHttp.Response send(HttpRequest request, UpdateProxy proxy, int idleMillis) throws IOException {
		URI target = request.uri();
		Socket socket = new Socket(java.net.Proxy.NO_PROXY);
		try {
			URI server = proxy.uri();
			socket.connect(new InetSocketAddress(server.getHost(), server.getPort()), 15_000);
			socket.setSoTimeout(30_000);
			if (server.getScheme().equals("https")) socket = tls(socket, server.getHost(), server.getPort());
			int port = target.getPort() == -1 ? (target.getScheme().equals("https") ? 443 : 80) : target.getPort();
			if (server.getScheme().equals("socks5")) socks(socket, target.getHost(), port, proxy);
			else connect(socket, target.getHost(), port, proxy);
			if (target.getScheme().equals("https")) socket = tls(socket, target.getHost(), port);
			else if (!target.getScheme().equals("http")) throw new IOException("无效更新协议");
			String path = target.getRawPath().isEmpty() ? "/" : target.getRawPath();
			if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
			StringBuilder headers = new StringBuilder("GET " + path + " HTTP/1.1\r\nHost: " + authority(target.getHost(), port) + "\r\nConnection: close\r\n");
			request.headers().map().forEach((name, values) -> values.forEach(value -> headers.append(name).append(": ").append(value).append("\r\n")));
			socket.getOutputStream().write(headers.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
			socket.getOutputStream().flush();
			InputStream input = new BufferedInputStream(socket.getInputStream());
			Head head;
			do { head = head(input); } while (head.status() == 100 || head.status() == 102 || head.status() == 103);
			socket.setSoTimeout(idleMillis);
			Socket connection = socket;
			InputStream body;
			String encoding = head.headers().firstValue("Transfer-Encoding").orElse("");
			if (encoding.equalsIgnoreCase("chunked")) body = new Chunked(input);
			else if (!encoding.isEmpty()) throw new IOException("不支持的更新传输编码");
			else {
				long length;
				try { length = head.headers().firstValueAsLong("Content-Length").orElse(-1); }
				catch (NumberFormatException e) { throw new IOException("无效更新响应大小", e); }
				body = length >= 0 ? new Limited(input, length) : input;
			}
			return new UpdateHttp.Response(head.status(), head.headers(), new FilterInputStream(body) {
				@Override public void close() throws IOException { connection.close(); }
			});
		} catch (IOException | RuntimeException e) {
			try { socket.close(); } catch (IOException ignored) {}
			if (e instanceof IOException io) throw io;
			throw new IOException("更新代理响应无效", e);
		}
	}

	private static Socket tls(Socket socket, String host, int port) throws IOException {
		SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
		var parameters = tls.getSSLParameters();
		parameters.setEndpointIdentificationAlgorithm("HTTPS");
		tls.setSSLParameters(parameters);
		tls.startHandshake();
		return tls;
	}

	private static String authority(String host, int port) {
		return (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
	}

	private static void connect(Socket socket, String host, int port, UpdateProxy proxy) throws IOException {
		String address = authority(host, port);
		String auth = proxy.authenticated() ? "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString(
			(proxy.account() + ":" + proxy.password()).getBytes(StandardCharsets.UTF_8)) + "\r\n" : "";
		socket.getOutputStream().write(("CONNECT " + address + " HTTP/1.1\r\nHost: " + address + "\r\n" + auth + "\r\n")
			.getBytes(StandardCharsets.ISO_8859_1));
		socket.getOutputStream().flush();
		if (head(socket.getInputStream()).status() != 200) throw new IOException("代理隧道连接失败");
	}

	private static void socks(Socket socket, String host, int port, UpdateProxy proxy) throws IOException {
		var output = socket.getOutputStream();
		var input = socket.getInputStream();
		output.write(proxy.authenticated() ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0}); output.flush();
		if (octet(input) != 5) throw new IOException("SOCKS 版本无效");
		int method = octet(input);
		if (method == 2 && proxy.authenticated()) {
			byte[] account = proxy.account().getBytes(StandardCharsets.UTF_8), password = proxy.password().getBytes(StandardCharsets.UTF_8);
			if (account.length > 255 || password.length > 255) throw new IOException("SOCKS 验证信息过长");
			output.write(1); output.write(account.length); output.write(account); output.write(password.length); output.write(password); output.flush();
			if (octet(input) != 1 || octet(input) != 0) throw new IOException("SOCKS 验证失败");
		} else if (method != 0) throw new IOException("SOCKS 验证方式不受支持");
		byte[] name = IDN.toASCII(host).getBytes(StandardCharsets.US_ASCII);
		if (name.length > 255) throw new IOException("SOCKS 目标地址过长");
		output.write(new byte[]{5, 1, 0, 3, (byte) name.length}); output.write(name);
		output.write(port >> 8); output.write(port & 255); output.flush();
		if (octet(input) != 5 || octet(input) != 0 || octet(input) != 0) throw new IOException("SOCKS 连接失败");
		int length = switch (octet(input)) {
			case 1 -> 4; case 3 -> octet(input); case 4 -> 16;
			default -> throw new IOException("SOCKS 响应地址无效");
		};
		for (int i = 0; i < length + 2; i++) octet(input);
	}

	private record Head(int status, HttpHeaders headers) {}

	private static Head head(InputStream input) throws IOException {
		String status = line(input);
		if (!status.matches("HTTP/1\\.[01] [0-9]{3}(?: .*)?")) throw new IOException("更新 HTTP 响应无效");
		var headers = new TreeMap<String, java.util.List<String>>(String.CASE_INSENSITIVE_ORDER);
		int size = status.length();
		for (String line; !(line = line(input)).isEmpty();) {
			if ((size += line.length() + 2) > 64 * 1024) throw new IOException("更新 HTTP 响应头过大");
			int colon = line.indexOf(':');
			if (colon <= 0) throw new IOException("更新 HTTP 响应头无效");
			headers.computeIfAbsent(line.substring(0, colon).trim(), ignored -> new ArrayList<>()).add(line.substring(colon + 1).trim());
		}
		return new Head(Integer.parseInt(status.substring(9, 12)), HttpHeaders.of(headers, (name, value) -> true));
	}

	private static int octet(InputStream input) throws IOException {
		int value = input.read();
		if (value < 0) throw new EOFException("更新连接提前关闭");
		return value;
	}

	private static String line(InputStream input) throws IOException {
		var result = new StringBuilder();
		for (int value; (value = octet(input)) != '\r';) {
			if (result.length() >= 8192 || value == '\n') throw new IOException("更新 HTTP 行无效");
			result.append((char) value);
		}
		if (octet(input) != '\n') throw new IOException("更新 HTTP 换行无效");
		return result.toString();
	}

	private static final class Limited extends FilterInputStream {
		private long remaining;
		Limited(InputStream input, long length) { super(input); remaining = length; }
		@Override public int read() throws IOException {
			byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
		}
		@Override public int read(byte[] bytes, int offset, int length) throws IOException {
			if (length == 0) return 0;
			if (remaining == 0) return -1;
			int count = in.read(bytes, offset, (int) Math.min(length, remaining));
			if (count < 0) throw new EOFException("更新下载尚未完成");
			remaining -= count; return count;
		}
	}

	private static final class Chunked extends FilterInputStream {
		private long remaining;
		private boolean started, finished;
		Chunked(InputStream input) { super(input); }
		@Override public int read() throws IOException {
			byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
		}
		@Override public int read(byte[] bytes, int offset, int length) throws IOException {
			if (length == 0) return 0;
			if (finished) return -1;
			if (remaining == 0) {
				if (started && !line(in).isEmpty()) throw new IOException("更新分块边界无效");
				String size = line(in).split(";", 2)[0];
				try { remaining = Long.parseLong(size, 16); }
				catch (NumberFormatException e) { throw new IOException("更新分块大小无效", e); }
				if (remaining < 0) throw new IOException("更新分块大小无效");
				started = true;
				if (remaining == 0) {
					int trailers = 0;
					for (String trailer; !(trailer = line(in)).isEmpty();) {
						if ((trailers += trailer.length() + 2) > 64 * 1024) throw new IOException("更新分块尾部过大");
					}
					finished = true; return -1;
				}
			}
			int count = in.read(bytes, offset, (int) Math.min(length, remaining));
			if (count < 0) throw new EOFException("更新分块尚未完成");
			remaining -= count; return count;
		}
	}
}
