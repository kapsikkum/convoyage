#!/usr/bin/env node
// Generates the wire types for both apps from the two source-of-truth schemas:
//   protocol/openapi.json         -> every components.schemas object  (REST)
//   protocol/convoy.v1.schema.json -> $defs objects and one struct per socket message (`t` value)
//
//   node protocol/codegen/generate.mjs           write the files
//   node protocol/codegen/generate.mjs --check   exit 1 if a generated file is stale (used by the server tests)
//
// Output (committed):
//   ios/Convoyage/Net/Generated/WireTypes.swift     Gen-prefixed Codable structs
//   android/app/src/main/java/app/convoyage/api/gen/WireTypes.kt   @Serializable data classes in app.convoyage.api.gen
//
// Everything optional on the wire is optional in the types, enums are plain strings (a new server value must not
// break decoding), and anything that is not a simple object/array/scalar becomes a raw JSON value.

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const openapi = JSON.parse(readFileSync(resolve(root, 'protocol/openapi.json'), 'utf8'));
const convoy = JSON.parse(readFileSync(resolve(root, 'protocol/convoy.v1.schema.json'), 'utf8'));

const SWIFT_RESERVED = new Set(['default', 'class', 'struct', 'enum', 'protocol', 'extension', 'func', 'let', 'var', 'in', 'is', 'as', 'self', 'Self', 'Type', 'init', 'internal', 'public', 'private', 'return', 'switch', 'case', 'for', 'while', 'if', 'else', 'do', 'try', 'throw', 'import', 'operator', 'where', 'super', 'true', 'false', 'nil', 'static', 'subscript', 'typealias', 'repeat', 'guard', 'defer', 'break', 'continue', 'fallthrough', 'associatedtype', 'rethrows', 'throws']);
const KOTLIN_RESERVED = new Set(['object', 'val', 'var', 'fun', 'class', 'interface', 'in', 'is', 'as', 'when', 'typealias', 'package', 'return', 'null', 'true', 'false', 'this', 'super', 'try', 'throw', 'do', 'while', 'for', 'if', 'else', 'break', 'continue', 'typeof']);

const pascal = (s) => s.split(/[^A-Za-z0-9]+/).filter(Boolean).map((p) => p[0].toUpperCase() + p.slice(1)).join('');

/** A schema reduced to what the generators need. */
function lower(schema, scope) {
  // scope: { refName(ref) -> [name, targetSchema], ... }
  if (!schema || typeof schema !== 'object') return { k: 'json' };
  if (schema.$ref) {
    const [name, target] = scope.resolve(schema.$ref);
    // Scalars and arrays behind a $ref are inlined; objects stay named.
    const inner = lower(target, scope);
    return inner.k === 'object' ? { k: 'ref', name } : inner;
  }
  if (schema.anyOf || schema.oneOf) {
    const options = (schema.anyOf ?? schema.oneOf);
    const nonNull = options.filter((o) => !(o && (o.type === 'null' || o.const === null)));
    if (nonNull.length === 1 && nonNull.length !== options.length) return { ...lower(nonNull[0], scope), nullable: true };
    return { k: 'json' };
  }
  let type = schema.type;
  let nullable = false;
  if (Array.isArray(type)) {
    nullable = type.includes('null');
    const rest = type.filter((t) => t !== 'null');
    type = rest.length === 1 ? rest[0] : undefined;
  }
  if (schema.const !== undefined) return { k: typeof schema.const === 'number' ? 'double' : 'string', nullable };
  switch (type) {
    case 'string': return { k: 'string', nullable };
    case 'integer': return { k: 'int', nullable };
    case 'number': return { k: 'double', nullable };
    case 'boolean': return { k: 'bool', nullable };
    case 'array': return { k: 'array', of: lower(schema.items, scope), nullable };
    case 'object':
      if (schema.properties) return { k: 'inline-object', schema, nullable };
      if (schema.additionalProperties && typeof schema.additionalProperties === 'object') return { k: 'map', of: lower(schema.additionalProperties, scope), nullable };
      return { k: 'json', nullable };
    default: return { k: 'json', nullable };
  }
}

/** Collect named object definitions: [{ name, schema, doc }]. Inline objects are hoisted as Parent+Field. */
function collect(scope, entries) {
  const out = [];
  const seen = new Set();
  const visit = (name, schema, doc) => {
    if (seen.has(name)) return;
    seen.add(name);
    const props = [];
    const required = new Set(schema.required ?? []);
    for (const [key, raw] of Object.entries(schema.properties ?? {})) {
      let t = lower(raw, scope);
      if (t.k === 'inline-object') {
        const child = name + pascal(key);
        visit(child, t.schema, raw.description);
        t = { k: 'ref', name: child, nullable: t.nullable };
      } else if (t.k === 'array' && t.of.k === 'inline-object') {
        const child = name + pascal(key) + 'Item';
        visit(child, t.of.schema, raw.items?.description);
        t = { ...t, of: { k: 'ref', name: child } };
      }
      props.push({ key, type: t, optional: !required.has(key) || !!t.nullable, doc: raw?.description });
    }
    out.push({ name, props, doc });
  };
  for (const [name, schema] of entries) {
    const t = lower(schema, scope);
    if (t.k === 'inline-object' || (schema.type === 'object' && schema.properties)) visit(name, schema, schema.description);
  }
  return out;
}

// --- scopes -------------------------------------------------------------------------------------------------------

const openapiScope = {
  resolve: (ref) => {
    const name = ref.replace('#/components/schemas/', '');
    return [name, openapi.components.schemas[name]];
  },
};
const convoyScope = {
  resolve: (ref) => {
    const name = ref.replace('#/$defs/', '');
    return [name, convoy.$defs[name]];
  },
};

const restDefs = collect(openapiScope, Object.entries(openapi.components.schemas));

// Socket: every $defs object, then one struct per message branch. Names get a Client/Server prefix.
const socketDefs = collect(convoyScope, Object.entries(convoy.$defs).filter(([n]) => n !== 'ClientMessage' && n !== 'ServerMessage'));
const messageNames = { client: [], server: [] };
for (const [side, key] of [['client', 'ClientMessage'], ['server', 'ServerMessage']]) {
  for (const branch of convoy.$defs[key].oneOf) {
    const t = branch.properties?.t?.const;
    if (!t) continue;
    const name = (side === 'client' ? 'Client' : 'Server') + pascal(t);
    messageNames[side].push({ t, name });
    socketDefs.push(...collect(convoyScope, [[name, { ...branch, description: branch.description }]]));
  }
}

// --- Swift --------------------------------------------------------------------------------------------------------

const swiftType = (t, prefix) => {
  const base = (() => {
    switch (t.k) {
      case 'string': return 'String';
      case 'int': return 'Int';
      case 'double': return 'Double';
      case 'bool': return 'Bool';
      case 'ref': return prefix + t.name;
      case 'array': return `[${swiftType({ ...t.of, nullable: false }, prefix)}]`;
      case 'map': return `[String: ${swiftType({ ...t.of, nullable: false }, prefix)}]`;
      default: return 'GenJSON';
    }
  })();
  return base;
};
const swiftIdent = (s) => (SWIFT_RESERVED.has(s) ? '`' + s + '`' : s);
const docLines = (doc, indent) => (doc ? doc.split('\n').map((l) => `${indent}/// ${l.trim()}`).join('\n') + '\n' : '');

function emitSwift() {
  let out = `// GENERATED by protocol/codegen/generate.mjs from protocol/openapi.json and protocol/convoy.v1.schema.json. Do not edit.
// Gen-prefixed so they sit next to the hand-written, lenient models in ApiModels.swift / Protocol.swift.

import Foundation

/// Any JSON value, for schema parts that are not a plain object, array or scalar.
enum GenJSON: Codable, Equatable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([GenJSON])
    case object([String: GenJSON])

    init(from decoder: Decoder) throws {
        let c: SingleValueDecodingContainer = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([GenJSON].self) { self = .array(v) }
        else { self = .object(try c.decode([String: GenJSON].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c: SingleValueEncodingContainer = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }
}
`;
  const emitGroup = (title, defs, prefix) => {
    out += `\n// MARK: ${title}\n`;
    for (const d of defs) {
      out += `\n${docLines(d.doc, '')}struct ${prefix}${d.name}: Codable, Equatable {\n`;
      for (const p of d.props) {
        out += docLines(p.doc, '    ');
        out += `    var ${swiftIdent(p.key)}: ${swiftType(p.type, prefix)}${p.optional ? '?' : ''}\n`;
      }
      if (!d.props.length) out += '    init() {}\n';
      out += '}\n';
    }
  };
  emitGroup('REST (openapi.json)', restDefs, 'Gen');
  emitGroup('Socket (convoy.v1.schema.json)', socketDefs, 'GenWs');
  out += '\n/// The `t` values of the socket protocol.\nenum GenWsType {\n';
  for (const side of ['client', 'server']) {
    for (const m of messageNames[side]) out += `    static let ${side}${pascal(m.t)}: String = "${m.t}"\n`;
  }
  out += '}\n';
  return out;
}

// --- Kotlin -------------------------------------------------------------------------------------------------------

const kotlinType = (t, prefix = '') => {
  switch (t.k) {
    case 'string': return 'String';
    case 'int': return 'Int';
    case 'double': return 'Double';
    case 'bool': return 'Boolean';
    case 'ref': return prefix + t.name;
    case 'array': return `List<${kotlinType({ ...t.of, nullable: false }, prefix)}>`;
    case 'map': return `Map<String, ${kotlinType({ ...t.of, nullable: false }, prefix)}>`;
    default: return 'JsonElement';
  }
};
const kotlinIdent = (s) => (KOTLIN_RESERVED.has(s) ? '`' + s + '`' : s);
const kdoc = (doc, indent) => (doc ? `${indent}/** ${doc.replace(/\*\//g, '* /').replace(/\n/g, ' ').trim()} */\n` : '');

function emitKotlin() {
  let out = `// GENERATED by protocol/codegen/generate.mjs from protocol/openapi.json and protocol/convoy.v1.schema.json. Do not edit.
// Separate package from the hand-written, lenient models in app.convoyage.api / app.convoyage.protocol.
@file:Suppress("unused")

package app.convoyage.api.gen

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
`;
  const emitGroup = (title, defs, prefix) => {
    out += `\n// ---- ${title} ----\n`;
    for (const d of defs) {
      out += `\n${kdoc(d.doc, '')}@Serializable\n`;
      if (!d.props.length) { out += `class ${prefix}${d.name}\n`; continue; }
      out += `data class ${prefix}${d.name}(\n`;
      for (const p of d.props) {
        out += kdoc(p.doc, '    ');
        out += `    val ${kotlinIdent(p.key)}: ${kotlinType(p.type, prefix)}${p.optional ? '? = null' : ''},\n`;
      }
      out += ')\n';
    }
  };
  emitGroup('REST (openapi.json)', restDefs, 'Gen');
  emitGroup('Socket (convoy.v1.schema.json)', socketDefs, 'GenWs');
  out += '\n/** The `t` values of the socket protocol. */\nobject GenWsType {\n';
  for (const side of ['client', 'server']) {
    for (const m of messageNames[side]) out += `    const val ${side.toUpperCase()}_${m.t.replace(/[^A-Za-z0-9]+/g, '_').toUpperCase()}: String = "${m.t}"\n`;
  }
  out += '}\n';
  return out;
}

// --- write / check ------------------------------------------------------------------------------------------------

const targets = [
  ['ios/Convoyage/Net/Generated/WireTypes.swift', emitSwift()],
  ['android/app/src/main/java/app/convoyage/api/gen/WireTypes.kt', emitKotlin()],
];

const check = process.argv.includes('--check');
let stale = 0;
for (const [rel, content] of targets) {
  const file = resolve(root, rel);
  const current = existsSync(file) ? readFileSync(file, 'utf8').replace(/\r\n/g, '\n') : null;
  if (check) {
    if (current !== content) { console.error(`stale: ${rel} (run: node protocol/codegen/generate.mjs)`); stale++; }
  } else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
    console.log(`wrote ${rel} (${content.split('\n').length} lines)`);
  }
}
if (stale) process.exit(1);
