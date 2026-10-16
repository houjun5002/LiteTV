"""Generates PinyinInitials.java.

The table is scoped rather than complete. A full Unihan table is ~21k CJK characters, which is
far more than a channel-name search will ever see and would be the largest thing in the APK; the
channel names in these playlists use around 700. Covering the common characters plus the corpus
gives room for channels that appear later without paying for the whole database.

Download once, then run:
    curl.exe -sSL --ssl-no-revoke -o Unihan.zip https://www.unicode.org/Public/UCD/latest/ucd/Unihan.zip
    python gen_pinyin.py Unihan.zip <out.java> [corpus.m3u ...]
"""

import io
import re
import sys
import zipfile


TONE_MARKS = {
    "\u0101": "a", "\u00e1": "a", "\u01ce": "a", "\u00e0": "a",
    "\u0113": "e", "\u00e9": "e", "\u011b": "e", "\u00e8": "e",
    "\u012b": "i", "\u00ed": "i", "\u01d0": "i", "\u00ec": "i",
    "\u014d": "o", "\u00f3": "o", "\u01d2": "o", "\u00f2": "o",
    "\u016b": "u", "\u00fa": "u", "\u01d4": "u", "\u00f9": "u",
    "\u01d6": "u", "\u01d8": "u", "\u01da": "u", "\u01dc": "u",
    "\u00fc": "v", "\u01d5": "v", "\u01d7": "v", "\u01d9": "v", "\u01db": "v",
}

# Characters that show up in station names but need not be in a given playlist: the provinces and
# major cities, the words for the kinds of channel there are, and the common connectives.
EXTRA_VOCABULARY = (
    "\u5317\u4eac\u4e0a\u6d77\u5929\u6d25\u91cd\u5e86\u6cb3\u5317\u5c71\u897f\u8fbd\u5b81"
    "\u5409\u6797\u9ed1\u9f99\u6c5f\u6c5f\u82cf\u6d59\u6c5f\u5b89\u5fbd\u798f\u5efa\u6c5f"
    "\u897f\u5c71\u4e1c\u6cb3\u5357\u6e56\u5317\u6e56\u5357\u5e7f\u4e1c\u5e7f\u897f\u6d77"
    "\u5357\u56db\u5ddd\u8d35\u5dde\u4e91\u5357\u9655\u897f\u7518\u8083\u9752\u6d77\u53f0"
    "\u6e7e\u5185\u8499\u53e4\u5b81\u590f\u65b0\u7586\u897f\u85cf\u6e2f\u6fb3\u7701\u5e02"
    "\u53bf\u5dde\u533a\u9547\u4e61\u6751\u5e7f\u64ad\u7535\u89c6\u53f0\u7f51\u7edc\u65b0"
    "\u95fb\u7efc\u5408\u9ad8\u6e05\u6807\u6e05\u8d85\u6e05\u5f71\u89c6\u7535\u5f71\u5267"
    "\u573a\u4f53\u80b2\u5a31\u4e50\u97f3\u4e50\u6b4c\u821e\u620f\u66f2\u5c11\u513f\u7ae5"
    "\u52a8\u753b\u7eaa\u5b9e\u4eba\u6587\u5386\u53f2\u5730\u7406\u79d1\u6559\u7ecf\u6d4e"
    "\u6cd5\u5236\u519c\u4e1a\u519b\u4e8b\u65c5\u6e38\u7f8e\u98df\u5065\u5eb7\u517b\u751f"
    "\u8d2d\u7269\u5546\u52a1\u5b66\u4e60\u804c\u4e1a\u751f\u6d3b\u670d\u52a1\u516c\u5171"
    "\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u767e\u5343\u4e07\u7b2c"
    "\u4e0a\u4e0b\u5de6\u53f3\u524d\u540e\u4e1c\u897f\u5357\u65b9\u56fd\u9645\u4e2d\u5916"
)


def load_unihan(zip_path):
    """char code -> pinyin initial, from Unihan's kMandarin readings."""
    table = {}
    with zipfile.ZipFile(zip_path) as archive:
        with archive.open("Unihan_Readings.txt") as handle:
            text = io.TextIOWrapper(handle, encoding="utf-8")
            for line in text:
                if line.startswith("#") or "\tkMandarin\t" not in line:
                    continue
                code, _, value = line.rstrip("\n").split("\t", 2)
                if not code.startswith("U+"):
                    continue
                syllable = value.split()[0]
                plain = "".join(TONE_MARKS.get(ch, ch) for ch in syllable).lower()
                plain = re.sub(r"[^a-z]", "", plain)
                if plain and plain[0] != "v":
                    table[int(code[2:], 16)] = plain[0]
    return table


def corpus_characters(paths):
    characters = set(EXTRA_VOCABULARY)
    for path in paths:
        with open(path, encoding="utf-8", errors="replace") as handle:
            body = handle.read()
        for name in re.findall(r'tvg-name="([^"]*)"', body):
            characters.update(name)
        for name in re.findall(r'group-title="([^"]*)"', body):
            characters.update(name)
        for name in re.findall(r',([^,\n]{1,40})$', body, re.M):
            characters.update(name)
    return characters


def main():
    unihan = load_unihan(sys.argv[1])
    out_path = sys.argv[2]
    wanted = corpus_characters(sys.argv[3:])

    selected = {}
    for character in wanted:
        code = ord(character)
        if 0x4E00 <= code <= 0x9FFF and code in unihan:
            selected[code] = unihan[code]

    by_letter = {}
    for code, letter in selected.items():
        by_letter.setdefault(letter, []).append(code)

    print("generating %d characters (corpus had %d, full table %d)"
          % (len(selected), len(wanted), len(unihan)), file=sys.stderr)

    out = []
    out.append("package com.cabletv.player.config;")
    out.append("")
    out.append("/**")
    out.append(" * The first letter of a Chinese character's pinyin, for searching channel names.")
    out.append(" *")
    out.append(" * <p>Typing a Chinese channel name on a television remote is not something anyone")
    out.append(" * wants to do, so search also matches a name's initials - and that needs to know how")
    out.append(" * each character is pronounced.")
    out.append(" *")
    out.append(" * <p>Generated from the Unicode Unihan database's kMandarin readings by")
    out.append(" * {@code tools/gen_pinyin.py}, so it is correct by construction rather than typed by")
    out.append(" * hand, and can be regenerated as the channel vocabulary changes. It is deliberately")
    out.append(" * scoped to the characters that appear in channel, group and station names rather")
    out.append(" * than the whole CJK block: the full table is some twenty thousand characters and")
    out.append(" * would be the largest thing in the APK, for a search that only ever sees these.")
    out.append(" *")
    out.append(" * <p>A character outside the table returns null, and the caller falls back to")
    out.append(" * matching the name itself - so a missing entry can only ever miss a match, never")
    out.append(" * make a wrong one.")
    out.append(" *")
    out.append(" * <p>Do not edit by hand.")
    out.append(" */")
    out.append("final class PinyinInitials {")
    out.append("")
    out.append("    private static final int FIRST_CHAR = 0x4E00;")
    out.append("    private static final int LAST_CHAR = 0x9FFF;")
    out.append("")
    out.append("    /** Indexed by character - FIRST_CHAR; 0 means unknown, otherwise 'a'..'z'. */")
    out.append("    private static final byte[] INITIALS = buildInitials();")
    out.append("")
    out.append("    private static byte[] buildInitials() {")
    out.append("        byte[] table = new byte[LAST_CHAR - FIRST_CHAR + 1];")
    for letter in sorted(by_letter):
        escaped = "".join("\\u%04X" % code for code in sorted(by_letter[letter]))
        out.append('        put(table, \'%s\', "%s");' % (letter, escaped))
    out.append("        return table;")
    out.append("    }")
    out.append("")
    out.append("    private static void put(byte[] table, char letter, String characters) {")
    out.append("        byte value = (byte) letter;")
    out.append("        for (int i = 0; i < characters.length(); i++) {")
    out.append("            int index = characters.charAt(i) - FIRST_CHAR;")
    out.append("            if (index >= 0 && index < table.length) {")
    out.append("                table[index] = value;")
    out.append("            }")
    out.append("        }")
    out.append("    }")
    out.append("")
    out.append("    /** @return the lowercase initial of the character, or null when it is unknown. */")
    out.append("    static String of(char c) {")
    out.append("        int index = c - FIRST_CHAR;")
    out.append("        if (index < 0 || index >= INITIALS.length) {")
    out.append("            return null;")
    out.append("        }")
    out.append("        byte value = INITIALS[index];")
    out.append("        return value == 0 ? null : String.valueOf((char) value);")
    out.append("    }")
    out.append("")
    out.append("    private PinyinInitials() {")
    out.append("    }")
    out.append("}")

    with open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(out) + "\n")
    print("wrote %s" % out_path, file=sys.stderr)


if __name__ == "__main__":
    main()
