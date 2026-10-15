"""Small JSON Schema (draft 2020-12) validator covering the keywords used by schema/knsproj-v1.schema.json.

The CLI has no third-party dependencies, so it ships this validator. If the `jsonschema` package is
installed it can be used instead (`--engine jsonschema`). `SUPPORTED_KEYWORDS` lists everything this
implementation understands; a test checks that the official schema uses nothing else.
"""

import re

SUPPORTED_KEYWORDS = {
    # validation
    "type", "properties", "required", "additionalProperties", "enum", "const", "items",
    "minItems", "maxItems", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
    "pattern", "minLength", "maxLength", "$ref", "oneOf", "anyOf", "allOf", "if", "then", "else",
    # annotations / structure
    "$schema", "$id", "$defs", "title", "description", "default", "examples",
}


class SchemaError:
    def __init__(self, path, message):
        self.path = path
        self.message = message

    def __repr__(self):
        return f"{self.path}: {self.message}"


def _type_ok(value, t):
    if t == "object":
        return isinstance(value, dict)
    if t == "array":
        return isinstance(value, list)
    if t == "string":
        return isinstance(value, str)
    if t == "boolean":
        return isinstance(value, bool)
    if t == "integer":
        return (isinstance(value, int) and not isinstance(value, bool)) or (isinstance(value, float) and value.is_integer())
    if t == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if t == "null":
        return value is None
    return False


def _describe(value):
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return "null"


class Validator:
    def __init__(self, schema):
        self.root = schema

    def _resolve(self, ref):
        if not ref.startswith("#/"):
            raise ValueError(f"only local $ref is supported: {ref}")
        node = self.root
        for part in ref[2:].split("/"):
            node = node[part.replace("~1", "/").replace("~0", "~")]
        return node

    def validate(self, instance):
        errors = []
        self._check(instance, self.root, "$", errors)
        return errors

    def is_valid(self, instance, schema):
        errs = []
        self._check(instance, schema, "$", errs)
        return not errs

    def _check(self, value, schema, path, errors):
        if schema is True or schema == {}:
            return
        if schema is False:
            errors.append(SchemaError(path, "no value is allowed here"))
            return
        if "$ref" in schema:
            self._check(value, self._resolve(schema["$ref"]), path, errors)

        if "type" in schema:
            types = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
            if not any(_type_ok(value, t) for t in types):
                errors.append(SchemaError(path, f"expected {' or '.join(types)}, got {_describe(value)}"))
                return

        if "const" in schema and value != schema["const"]:
            errors.append(SchemaError(path, f"must be {schema['const']!r}"))
        if "enum" in schema and value not in schema["enum"]:
            shown = ", ".join(map(str, schema["enum"][:12])) + (", ..." if len(schema["enum"]) > 12 else "")
            errors.append(SchemaError(path, f"{value!r} is not one of: {shown}"))

        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if "minimum" in schema and value < schema["minimum"]:
                errors.append(SchemaError(path, f"must be >= {schema['minimum']}"))
            if "maximum" in schema and value > schema["maximum"]:
                errors.append(SchemaError(path, f"must be <= {schema['maximum']}"))
            if "exclusiveMinimum" in schema and value <= schema["exclusiveMinimum"]:
                errors.append(SchemaError(path, f"must be > {schema['exclusiveMinimum']}"))
            if "exclusiveMaximum" in schema and value >= schema["exclusiveMaximum"]:
                errors.append(SchemaError(path, f"must be < {schema['exclusiveMaximum']}"))

        if isinstance(value, str):
            if "minLength" in schema and len(value) < schema["minLength"]:
                errors.append(SchemaError(path, f"must have at least {schema['minLength']} characters"))
            if "maxLength" in schema and len(value) > schema["maxLength"]:
                errors.append(SchemaError(path, f"must have at most {schema['maxLength']} characters"))
            if "pattern" in schema and not re.search(schema["pattern"], value):
                errors.append(SchemaError(path, f"{value!r} does not match {schema['pattern']}"))

        if isinstance(value, list):
            if "minItems" in schema and len(value) < schema["minItems"]:
                errors.append(SchemaError(path, f"needs at least {schema['minItems']} items"))
            if "maxItems" in schema and len(value) > schema["maxItems"]:
                errors.append(SchemaError(path, f"allows at most {schema['maxItems']} items"))
            if "items" in schema:
                for i, item in enumerate(value):
                    self._check(item, schema["items"], f"{path}[{i}]", errors)

        if isinstance(value, dict):
            props = schema.get("properties", {})
            for key in schema.get("required", []):
                if key not in value:
                    errors.append(SchemaError(path, f"missing required property '{key}'"))
            for key, item in value.items():
                if key in props:
                    self._check(item, props[key], f"{path}.{key}", errors)
                else:
                    extra = schema.get("additionalProperties", True)
                    if extra is False:
                        hint = _closest(key, props.keys())
                        errors.append(SchemaError(f"{path}.{key}", "unknown property" + (f" (did you mean '{hint}'?)" if hint else "")))
                    elif isinstance(extra, dict):
                        self._check(item, extra, f"{path}.{key}", errors)

        if "allOf" in schema:
            for sub in schema["allOf"]:
                self._check(value, sub, path, errors)
        if "anyOf" in schema:
            if not any(self.is_valid(value, sub) for sub in schema["anyOf"]):
                errors.append(self._best_branch_error(value, schema["anyOf"], path, "anyOf"))
        if "oneOf" in schema:
            matches = sum(1 for sub in schema["oneOf"] if self.is_valid(value, sub))
            if matches != 1:
                if matches == 0:
                    errors.append(self._best_branch_error(value, schema["oneOf"], path, "oneOf"))
                else:
                    errors.append(SchemaError(path, "matches more than one alternative"))
        if "if" in schema:
            if self.is_valid(value, schema["if"]):
                if "then" in schema:
                    self._check(value, schema["then"], path, errors)
            elif "else" in schema:
                self._check(value, schema["else"], path, errors)

    def _best_branch_error(self, value, branches, path, keyword):
        """Reports the error of the alternative that got furthest, which is usually the intended one."""
        best = None
        for sub in branches:
            errs = []
            self._check(value, sub, path, errs)
            # Prefer branches whose discriminating "type"/const matched (errors deeper than the root).
            score = (min((e.path.count(".") + e.path.count("[") for e in errs), default=0), -len(errs))
            if best is None or score > best[0]:
                best = (score, errs)
        if best and best[1]:
            e = max(best[1], key=lambda x: x.path.count(".") + x.path.count("["))
            return SchemaError(e.path, e.message)
        return SchemaError(path, f"does not match any allowed form ({keyword})")


def _closest(key, candidates):
    import difflib
    m = difflib.get_close_matches(key, list(candidates), n=1, cutoff=0.6)
    return m[0] if m else None


def collect_keywords(schema, out=None):
    """All keywords used by a schema document (for the supported-keyword test)."""
    if out is None:
        out = set()
    if isinstance(schema, dict):
        for k, v in schema.items():
            out.add(k)
            if k in ("properties", "$defs"):
                for sub in v.values():
                    collect_keywords(sub, out)
            elif k in ("items", "additionalProperties", "if", "then", "else", "not"):
                collect_keywords(v, out)
            elif k in ("oneOf", "anyOf", "allOf"):
                for sub in v:
                    collect_keywords(sub, out)
    return out
