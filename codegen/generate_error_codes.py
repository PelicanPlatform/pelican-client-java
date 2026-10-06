#!/usr/bin/env python3
"""Generate PelicanErrorCode.java from the Pelican error-code registry.

The registry (docs/error_codes.yaml in the PelicanPlatform/pelican repo) is the
single source of truth for the numeric code, dotted type name, client exit code
and -- most importantly -- the `retryable` flag of every client-visible failure.
Transcribing it by hand into Java would guarantee that the Go client and this one
eventually disagree about whether a failure is worth retrying, which is a class of
bug that only shows up in production.  So: vendor the YAML, generate the enum.

Usage:  codegen/refresh.sh [path-to-pelican-checkout]
"""
import pathlib
import re
import sys

import yaml

HERE = pathlib.Path(__file__).resolve().parent
YAML = HERE / "error_codes.yaml"
OUT = (
    HERE.parent
    / "pelican-client-core/src/main/java/org/pelicanplatform/client/PelicanErrorCode.java"
)

HEADER = """/***************************************************************
 *
 * Copyright (C) 2026, Pelican Project, Morgridge Institute for Research
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ***************************************************************/
"""


def enum_name(dotted: str) -> str:
    """Parameter.FileNotFound -> PARAMETER_FILE_NOT_FOUND"""
    parts = []
    for segment in dotted.split("."):
        parts.extend(re.findall(r"[A-Z]+(?![a-z])|[A-Z][a-z0-9]*|[a-z0-9]+", segment))
    return "_".join(p.upper() for p in parts)


def wrap(text: str, width: int, indent: str) -> list[str]:
    words, lines, current = text.split(), [], ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if len(candidate) + len(indent) > width and current:
            lines.append(indent + current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(indent + current)
    return lines


def main() -> int:
    docs = [d for d in yaml.safe_load_all(YAML.read_text()) if d]
    entries = []
    seen = set()
    for doc in docs:
        if "type" not in doc or "code" not in doc:
            continue
        name = enum_name(doc["type"])
        if name in seen:
            raise SystemExit(f"duplicate enum name {name} for type {doc['type']}")
        seen.add(name)
        entries.append(
            {
                "name": name,
                "type": doc["type"],
                "code": int(doc["code"]),
                "exit": int(doc.get("clientExitCode", 0)),
                "retryable": bool(doc.get("retryable", False)),
                "description": " ".join(str(doc.get("description", "")).split()),
            }
        )

    out = [HEADER, ""]
    out.append("package org.pelicanplatform.client;")
    out.append("")
    out.append("import java.util.Map;")
    out.append("import java.util.Optional;")
    out.append("import java.util.function.Function;")
    out.append("import java.util.stream.Collectors;")
    out.append("import java.util.stream.Stream;")
    out.append("")
    out.append("/**")
    out.append(" * Client-visible Pelican error codes.")
    out.append(" *")
    out.append(" * <p>GENERATED FILE -- DO NOT EDIT.  Produced by {@code codegen/generate_error_codes.py}")
    out.append(" * from {@code docs/error_codes.yaml} in the PelicanPlatform/pelican repository, so that")
    out.append(" * this client and the Go client cannot drift on what a code means or on whether a")
    out.append(" * failure is retryable.  Run {@code codegen/refresh.sh} to regenerate.")
    out.append(" */")
    out.append("public enum PelicanErrorCode {")
    for i, e in enumerate(entries):
        if e["description"]:
            out.append("    /**")
            out.extend(wrap(e["description"], 100, "     * "))
            out.append("     */")
        terminator = "," if i < len(entries) - 1 else ";"
        out.append(
            '    %s("%s", %d, %d, %s)%s'
            % (e["name"], e["type"], e["code"], e["exit"], str(e["retryable"]).lower(), terminator)
        )
    out.append("")
    out.append("    private static final Map<Integer, PelicanErrorCode> BY_CODE =")
    out.append("            Stream.of(values())")
    out.append("                    .collect(Collectors.toUnmodifiableMap(PelicanErrorCode::code, Function.identity()));")
    out.append("")
    out.append("    private static final Map<String, PelicanErrorCode> BY_TYPE =")
    out.append("            Stream.of(values())")
    out.append("                    .collect(Collectors.toUnmodifiableMap(PelicanErrorCode::type, Function.identity()));")
    out.append("")
    out.append("    private final String type;")
    out.append("    private final int code;")
    out.append("    private final int clientExitCode;")
    out.append("    private final boolean retryable;")
    out.append("")
    out.append("    PelicanErrorCode(String type, int code, int clientExitCode, boolean retryable) {")
    out.append("        this.type = type;")
    out.append("        this.code = code;")
    out.append("        this.clientExitCode = clientExitCode;")
    out.append("        this.retryable = retryable;")
    out.append("    }")
    out.append("")
    out.append("    /** The dotted type name, e.g. {@code Transfer.DirectorTimeout}. */")
    out.append("    public String type() {")
    out.append("        return type;")
    out.append("    }")
    out.append("")
    out.append("    /** The numeric code shared with the Go client. */")
    out.append("    public int code() {")
    out.append("        return code;")
    out.append("    }")
    out.append("")
    out.append("    /** The process exit code the CLI would use for this failure. */")
    out.append("    public int clientExitCode() {")
    out.append("        return clientExitCode;")
    out.append("    }")
    out.append("")
    out.append("    /** Whether re-running the whole operation could plausibly succeed. */")
    out.append("    public boolean retryable() {")
    out.append("        return retryable;")
    out.append("    }")
    out.append("")
    out.append("    public static Optional<PelicanErrorCode> byCode(int code) {")
    out.append("        return Optional.ofNullable(BY_CODE.get(code));")
    out.append("    }")
    out.append("")
    out.append("    public static Optional<PelicanErrorCode> byType(String type) {")
    out.append("        return Optional.ofNullable(BY_TYPE.get(type));")
    out.append("    }")
    out.append("}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out) + "\n")
    print(f"wrote {OUT} ({len(entries)} codes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
