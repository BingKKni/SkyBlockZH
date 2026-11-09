package io.github.bingkkni.skyzh.compat;

import java.lang.reflect.Method;

/** Optional IMBlocker 5.1+ focus contract for MoulConfig's always-available search input. */
public final class InputMethodCompat {
	private Object container;
	private Method getPreferred;
	private Method setPreferred;
	private Boolean previous;
	private boolean resolved;

	/** Run after screen-change listeners, which reset IMBlocker's container preference. */
	public void typing() {
		if (!resolved) {
			resolved = true;
			try {
				Class<?> type = Class.forName("io.github.reserveword.imblocker.common.gui.FocusContainer");
				container = type.getField("MINECRAFT").get(null);
				getPreferred = type.getMethod("getPreferredState");
				setPreferred = type.getMethod("setPreferredState", boolean.class);
			} catch (ClassNotFoundException ignored) {
				return;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				disable(e); return;
			}
		}
		if (container == null) return;
		try {
			boolean current = (boolean) getPreferred.invoke(container);
			if (previous == null) previous = current;
			if (!current) setPreferred.invoke(container, true);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) { disable(e); }
	}

	/** Called before leaving this screen, including temporary link confirmations. */
	public void removed() {
		if (container == null || previous == null) return;
		try { setPreferred.invoke(container, previous.booleanValue()); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError e) { disable(e); }
		finally { previous = null; }
	}

	private void disable(Throwable error) {
		container = null;
		org.slf4j.LoggerFactory.getLogger("SkyZH").warn("IMBlocker 输入焦点兼容接口不可用", error);
	}
}
