package io.github.bingkkni.skyzh;

/** Runtime display policy; unknown saved values deliberately retain compatible behavior. */
public enum DisplayPriority {
	DEFAULT("default"), HIGH("high"), FORCE("force");

	private final String key;

	DisplayPriority(String key) { this.key = key; }
	public String key() { return this.key; }

	public static DisplayPriority parse(String value) {
		for (DisplayPriority priority : values()) {
			if (priority.key.equalsIgnoreCase(value)) return priority;
		}
		return DEFAULT;
	}
}
