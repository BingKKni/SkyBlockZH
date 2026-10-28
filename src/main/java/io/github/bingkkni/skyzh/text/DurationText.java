package io.github.bingkkni.skyzh.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** Final formatting for authored Chinese time units, including units outside a duration capture. */
public final class DurationText {
	private static final String UNIT = "(-?[0-9][0-9,.]*) *(年|个月|天|小时|分钟|分|秒)";
	private static final Pattern GROUP = Pattern.compile(UNIT + "(?: *" + UNIT + ")*");
	private static final Pattern PART = Pattern.compile(UNIT);

	private DurationText() {}

	/** Keep every source number/unit's own style; never rewrite names or numeric clock/race strings. */
	public static MutableComponent normalize(Component source) {
		String text = source.getString();
		if (!text.contains("秒") && !text.contains("分") && !text.contains("小时")
			&& !text.contains("天") && !text.contains("年") && !text.contains("个月")) return source.copy();
		StyledText styled = StyledText.of(source);
		Matcher groups = GROUP.matcher(styled.plain());
		MutableComponent result = Component.empty();
		int cursor = 0;
		while (groups.find()) {
			Matcher parts = PART.matcher(styled.plain().substring(groups.start(), groups.end()));
			int count = 0;
			while (parts.find()) count++;
			parts.reset();
			// An isolated 分 can mean points, not minutes. Duration captures already disambiguate it.
			if (count == 1 && groups.group(2).equals("分")) continue;
			if (count == 1 && groups.group(2).equals("年")
				&& (styled.plain().substring(0, groups.start()).matches("(?s).*第 *")
					|| styled.plain().substring(groups.end()).matches("(?s)^ *(?:[0-9]+ *月|[春夏秋冬]季).*"))) continue;
			result.append(styled.slice(cursor, groups.start()));
			var spans = new java.util.ArrayList<int[]>();
			boolean hours = false;
			while (parts.find()) {
				spans.add(new int[] {parts.start(1), parts.end(1), parts.start(2), parts.end(2)});
				hours |= parts.group(2).equals("小时");
			}
			while (hours && spans.size() > 1) {
				int[] last = spans.getLast();
				String unit = groups.group().substring(last[2], last[3]);
				if ((!unit.equals("分") && !unit.equals("分钟") && !unit.equals("秒"))
					|| !groups.group().substring(last[0], last[1]).matches("0+")) break;
				spans.removeLast();
			}
			parts.reset();
			int index = 0;
			while (index < spans.size() && parts.find()) {
				int start = groups.start() + parts.start(1);
				if (index > 0 && parts.group(2).equals("秒") && parts.group(1).matches("[0-9]")) {
					result.append(Component.literal("0").setStyle(styled.styleAt(start)));
				}
				result.append(styled.slice(start, groups.start() + parts.end(1)));
				int unitStart = groups.start() + parts.start(2);
				int unitEnd = groups.start() + parts.end(2);
				if (count > 1 && parts.group(2).equals("分钟")) unitEnd--;
				result.append(styled.slice(unitStart, unitEnd));
				index++;
			}
			cursor = groups.end();
		}
		return result.append(styled.slice(cursor, styled.length()));
	}
}
