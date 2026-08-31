"""Resolves every Mixin target in a built jar against the Minecraft it was built for.

WHY THIS EXISTS
---------------
Every injector in this mod is `require = 0`, on purpose: another mod redirecting the same instruction
would otherwise make ours impossible to apply, and refusing to boot somebody's modpack over a
translation is the wrong trade. The cost of that choice is that a target which stopped matching is
*silent*. The build succeeds, the game starts, the log says the corpus loaded — and one surface is
quietly English forever, because a descriptor gained a parameter or a class was split in two.

Supporting a second Minecraft turns that from a risk into a near-certainty. 26.2 renamed the HUD class,
moved the entity type constants and dropped a `double` from `submitNameTag`; each of those is exactly
the kind of change that leaves a `require = 0` hook applying to nothing. Compiling proves the mod's own
source agrees with itself, and proves nothing whatever about whether the injection points still exist.

So this reads the annotations back out of the compiled mixin classes — the real `@Mixin` value, the real
`method` list, the real `At.target` — and looks each one up in the Minecraft on the compile classpath.
It is the check that `require = 0` costs us, paid back.

WHAT IT CHECKS
--------------
For each mixin class named in the config:
  * every `@Mixin` target class exists;
  * every `method` named by an injector exists on one of those classes (by name, or by name and exact
    descriptor when the annotation gives one);
  * every `At.target` call site appears in the bytecode of the method it is supposed to be found in;
  * every `@Shadow` field or method exists on the target — these are not `require = 0` and a missing
    one is a crash at apply time rather than a silent miss.

USAGE
-----
    python gradle/check_mixin_targets.py <mixin-config.json> <classes-dir> <classpath-file>

`classpath-file` holds the compile classpath, one entry per line, as written by the Gradle task in
gradle/target.gradle. Passing the classpath the mod was actually compiled against — rather than a jar
this script went looking for — is what makes the answer trustworthy.
"""
import json
import os
import re
import subprocess
import sys

# Annotations that name a target method and may name a call site inside it.
INJECTORS = (
    "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable",
    "ModifyConstant", "ModifyReturnValue", "WrapOperation", "WrapWithCondition",
)


def run_javap(classpath, class_name):
    """javap -v for one class, or None when the class is not on the classpath."""
    result = subprocess.run(
        ["javap", "-v", "-p", "-cp", classpath, class_name],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if result.returncode != 0 or not result.stdout.strip():
        return None
    return result.stdout


class Disassembly:
    """One class's javap output, indexed by member."""

    def __init__(self, text):
        self.text = text
        self.methods = {}   # name -> list of (descriptor, body text)
        self.fields = set()
        self._parse()

    def _parse(self):
        # javap prints members at two-space indent, each followed by an indented block holding
        # `descriptor:`, flags, Code and attributes. A member ends where the next one starts.
        lines = self.text.splitlines()
        start = next((i for i, l in enumerate(lines) if l.startswith("{")), 0)
        current = None
        blocks = []
        for line in lines[start + 1:]:
            if line.startswith("}"):
                break
            if line.startswith("  ") and not line.startswith("   ") and line.strip():
                if current is not None:
                    blocks.append(current)
                current = [line]
            elif current is not None:
                current.append(line)
        if current is not None:
            blocks.append(current)

        for block in blocks:
            head = block[0].strip()
            body = "\n".join(block)
            descriptor = None
            for line in block:
                match = re.match(r"\s*descriptor:\s*(\S+)", line)
                if match:
                    descriptor = match.group(1)
                    break
            if "(" in head:
                name = self._method_name(head)
                if name:
                    self.methods.setdefault(name, []).append((descriptor, body))
            else:
                name = head.rstrip(";").split()[-1] if head.rstrip(";").split() else None
                if name:
                    self.fields.add(name)

    @staticmethod
    def _method_name(head):
        """`private void skyzh$noteEntity(net.minecraft…)` -> `skyzh$noteEntity`."""
        before = head.split("(", 1)[0].strip()
        if not before:
            return None
        last = before.split()[-1]
        # A constructor prints as the fully qualified class name; a generic method may carry a type
        # parameter list before its name.
        return last.rsplit(".", 1)[-1]

    def has_method(self, name, descriptor=None):
        overloads = self.methods.get(name)
        if not overloads:
            return False
        if descriptor is None:
            return True
        return any(found == descriptor for found, _ in overloads)

    def bodies(self, name, descriptor=None):
        overloads = self.methods.get(name, [])
        if descriptor is None:
            return [body for _, body in overloads]
        return [body for found, body in overloads if found == descriptor]


def constant_pool(text):
    """Read the small part of javap's constant pool needed to decode compact annotations.

    Recent JDKs print annotations as e.g. ``0: #79(#69=[c#81])`` rather than spelling out
    ``@Mixin(value={class L...;})``. The old parser happened to work with an older javap format,
    but failed every annotation as soon as the build moved to JDK 25. Keeping the decoder here
    avoids making the check depend on a particular JDK's pretty-printer.
    """
    pool = {}
    for line in text.splitlines():
        match = re.match(r"\s*#(\d+) = (Utf8|Class|String)\s+(.*)$", line)
        if match:
            pool[int(match.group(1))] = (match.group(2), match.group(3))
    return pool


def pool_value(pool, number, kind=None):
    """Resolve a Utf8/String/Class constant to its printable value."""
    entry = pool.get(int(number))
    if entry is None:
        return None
    entry_kind, value = entry
    if entry_kind == "Utf8":
        return value
    reference = re.fullmatch(r"#(\d+)", value)
    if reference:
        # `String #n` and `Class #n` both point to a Utf8 constant. The caller already knows
        # whether the original annotation token was `s#` or `c#`; the referenced value itself is
        # just the string we need to compare or put into a descriptor.
        return pool_value(pool, int(reference.group(1)))
    return value


def annotation_type_refs(block, pool):
    """Return annotation descriptor strings whose constant-pool references occur in a member."""
    result = []
    for number in re.findall(r"#(\d+)", block):
        value = pool_value(pool, number, "Utf8")
        if value and value.startswith("Lorg/spongepowered/asm/mixin/"):
            result.append(value)
    return result


def annotation_values(block, pool, key, value_prefix):
    """Resolve values belonging to an annotation key in javap's compact annotation syntax."""
    key_numbers = [str(number) for number, entry in pool.items()
                   if entry == ("Utf8", key)]
    values = []
    for number in key_numbers:
        # An annotation entry is `#key=[s#value]` or `#key=s#value`. Restrict the search to the
        # value after this key; otherwise a later nested @At entry could be mistaken for it.
        for match in re.finditer(r"#" + re.escape(number) + r"=([^,)]*|\[[^]]*\])", block):
            raw = match.group(1)
            for ref in re.findall(re.escape(value_prefix) + r"#(\d+)", raw):
                value = pool_value(pool, ref, "Utf8")
                if value is not None:
                    values.append(value)
    return values


def parse_target_classes(text):
    """The classes named by `@Mixin(...)`, as internal names."""
    pool = constant_pool(text)
    # Class annotations are outside the member table. Looking for the Mixin descriptor among the
    # annotation references is enough, since an injector's nested @At annotation never has this
    # descriptor.
    if not any(value == "Lorg/spongepowered/asm/mixin/Mixin;"
               for value in annotation_type_refs(text, pool)):
        return []

    found = []
    # `@Mixin(value={Target.class})` is emitted as `c#N` and `@Mixin(targets="...")` as `s#N`.
    # There are no class constants in the nested injector annotations used by this project, so
    # reading these references from the complete disassembly is safe and avoids depending on the
    # JDK's line wrapping for the class-level annotation.
    for ref in re.findall(r"c#(\d+)", text):
        value = pool_value(pool, ref)
        if value:
            if value.startswith("L") and value.endswith(";"):
                value = value[1:-1]
            found.append(value)
    found += [value.replace(".", "/") for value in annotation_values(text, pool, "targets", "s")]
    return list(dict.fromkeys(found))


def member_blocks(text):
    lines = text.splitlines()
    start = next((i for i, l in enumerate(lines) if l.startswith("{")), 0)
    current = None
    blocks = []
    for line in lines[start + 1:]:
        if line.startswith("}"):
            break
        if line.startswith("  ") and not line.startswith("   ") and line.strip():
            if current is not None:
                blocks.append(current)
            current = [line]
        elif current is not None:
            current.append(line)
    if current is not None:
        blocks.append(current)
    return ["\n".join(block) for block in blocks]


def parse_members(text):
    """Per mixin member: its injector kind, target methods/calls, and shadows."""
    injectors = []
    shadows = []
    pool = constant_pool(text)

    for body in member_blocks(text):
        lines = body.splitlines()
        head = lines[0].strip()
        if "RuntimeVisibleAnnotations" not in body and "RuntimeInvisibleAnnotations" not in body:
            continue

        member = Disassembly._method_name(head) if "(" in head else head.rstrip(";").split()[-1]
        annotation_types = annotation_type_refs(body, pool)

        if "Lorg/spongepowered/asm/mixin/Shadow;" in annotation_types:
            shadows.append((member, "(" in head))
            continue

        kind = next((k for k in INJECTORS
                     if "Lorg/spongepowered/asm/mixin/injection/%s;" % k in annotation_types), None)
        if kind is None:
            continue

        methods = annotation_values(body, pool, "method", "s")
        targets = annotation_values(body, pool, "target", "s")
        at_values = annotation_values(body, pool, "value", "s")

        injectors.append({
            "member": member,
            "kind": kind,
            "methods": methods,
            "targets": targets,
            "at_values": at_values,
        })

    return injectors, shadows


def split_target(target):
    """`Lowner;name(args)ret` -> (owner, name, `(args)ret`). Field targets have no parenthesis."""
    match = re.match(r"L([^;]+);([^(:]+)(\(.*)$", target)
    if match:
        return match.group(1), match.group(2), match.group(3)
    match = re.match(r"L([^;]+);([^(:]+):(.+)$", target)
    if match:
        return match.group(1), match.group(2), match.group(3)
    return None, None, None


def callsite_needles(target):
    """The strings javap would print for a call to `target`.

    javap writes `owner.name:(desc)` in the constant-pool comment, and omits the owner entirely when
    the call is on the class being disassembled — so both forms are accepted.
    """
    owner, name, descriptor = split_target(target)
    if owner is None:
        return []
    return ["%s.%s:%s" % (owner, name, descriptor), "%s %s:%s" % ("Method", name, descriptor)]


def main(argv):
    if len(argv) != 3:
        print(__doc__)
        return 2

    config_path, classes_dir, classpath_file = argv
    classpath = os.pathsep.join(
        [classes_dir] + [l.strip() for l in open(classpath_file, encoding="utf-8") if l.strip()]
    )

    config = json.loads(open(config_path, encoding="utf-8").read())
    package = config["package"]
    names = []
    for key in ("mixins", "client", "server"):
        names += config.get(key, [])

    problems = []
    checked = 0
    disassembled = {}

    # A mixin class that exists and is not listed in the config is never applied, and nothing anywhere
    # says so — the same silent failure this whole script exists to catch, arrived at from the other
    # direction. Cheap to rule out: the package is one directory.
    package_dir = os.path.join(classes_dir, *package.split("."))
    if os.path.isdir(package_dir):
        listed = set(names)
        for entry in sorted(os.listdir(package_dir)):
            if not entry.endswith(".class") or "$" in entry:
                continue
            simple = entry[: -len(".class")]
            checked += 1
            if simple not in listed:
                problems.append(
                    "%s: 编译出来了但没写进 %s 的列表里，等于完全不会生效"
                    % (simple, os.path.basename(config_path))
                )

    def target_class(internal_name):
        if internal_name not in disassembled:
            text = run_javap(classpath, internal_name.replace("/", "."))
            disassembled[internal_name] = Disassembly(text) if text else None
        return disassembled[internal_name]

    print("Mixin 目标核对（%s）" % os.path.basename(config_path))

    for simple in names:
        mixin_name = "%s.%s" % (package, simple)
        text = run_javap(classpath, mixin_name)
        if text is None:
            problems.append("%s: 编译产物里找不到这个 mixin 类" % simple)
            continue

        target_names = parse_target_classes(text)
        if not target_names:
            problems.append("%s: 读不到 @Mixin 的目标类" % simple)
            continue

        targets = {}
        for internal_name in target_names:
            disassembly = target_class(internal_name)
            if disassembly is None:
                problems.append("%s: 目标类不存在 -> %s" % (simple, internal_name))
                continue
            targets[internal_name] = disassembly

        if not targets:
            continue

        injectors, shadows = parse_members(text)

        for shadow, is_method in shadows:
            ok = any(
                (disassembly.has_method(shadow) if is_method else shadow in disassembly.fields)
                for disassembly in targets.values()
            )
            checked += 1
            if not ok:
                problems.append("%s: @Shadow %s 在目标类里不存在（这个不是 require=0，会在应用时崩）"
                                % (simple, shadow))

        for injector in injectors:
            for method in injector["methods"]:
                name = method.split("(", 1)[0]
                descriptor = "(" + method.split("(", 1)[1] if "(" in method else None

                bodies = []
                for disassembly in targets.values():
                    bodies += disassembly.bodies(name, descriptor)

                checked += 1
                if not bodies:
                    problems.append(
                        "%s#%s (%s): 目标方法不存在 -> %s"
                        % (simple, injector["member"], injector["kind"], method)
                    )
                    continue

                for target in injector["targets"]:
                    needles = callsite_needles(target)
                    checked += 1
                    if not needles:
                        problems.append("%s#%s: 看不懂的 At target -> %s"
                                        % (simple, injector["member"], target))
                        continue
                    if not any(needle in body for body in bodies for needle in needles):
                        problems.append(
                            "%s#%s (%s): %s 里没有这个调用点 -> %s"
                            % (simple, injector["member"], injector["kind"], method, target)
                        )

    print("  检查了 %d 处目标（%d 个 mixin 类）" % (checked, len(names)))

    if problems:
        print("\n以下目标对不上，注入会静默失效：")
        for problem in problems:
            print("  [失败] " + problem)
        print("\n失败 %d 处" % len(problems))
        return 1

    print("  全部命中")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
