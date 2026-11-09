package io.github.bingkkni.skyzh.update;

import java.net.URI;
import java.util.Locale;

/** Immutable snapshot: changing credentials never alters an in-flight request. */
public final class UpdateProxy {
	public static final UpdateProxy NONE = new UpdateProxy("", "", "");
	private final String url;
	private final String account;
	private final String password;

	public UpdateProxy(String url, String account, String password) {
		this.url = url; this.account = account; this.password = password;
	}

	public static String address(String protocol, String address) {
		if (protocol == null || address == null) throw new IllegalArgumentException("无效代理地址");
		protocol = protocol.toLowerCase(Locale.ROOT);
		if (!java.util.List.of("http", "https", "socks5").contains(protocol)) throw new IllegalArgumentException();
		try {
			URI uri = new URI(protocol + "://" + address).parseServerAuthority();
			if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
				|| uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
				|| uri.getRawQuery() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
			return uri.toASCIIString();
		} catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("无效代理地址", e); }
	}

	public String url() { return url; }
	public String account() { return account; }
	public String password() { return password; }
	public boolean authenticated() { return !account.isEmpty() || !password.isEmpty(); }
	public boolean configured() { return !url.isEmpty(); }
	public URI uri() { return URI.create(url); }
	public UpdateProxy withUrl(String value) { return new UpdateProxy(value, account, password); }
	public UpdateProxy withAccount(String value) { return new UpdateProxy(url, value, password); }
	public UpdateProxy withPassword(String value) { return new UpdateProxy(url, account, value); }
	// Deliberately no generated toString: credentials must not appear in update logs.
}
