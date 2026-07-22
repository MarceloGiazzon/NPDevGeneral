/**
 * LIFT-EXPR-P5: client-side mirror of the server's boolean-complete grammar
 * (`com.npdev.dsl.v1.expr.ComputedExpression`) for live authoring feedback only.
 *
 * Deliberately a minimal parser, not an evaluator: it exists to give the invariant editor
 * instant parse/boolean-shape/field-reference feedback without a network round-trip. The
 * server-side `ComputedExpression` + `SemanticValidator` remain the source of truth at
 * `validateModel` time; this must stay a strict syntactic subset so it never accepts something
 * the server rejects. Expressions using CEL-specific syntax the server also delegates around
 * (`.matches()`, `.uniqueBy()`, `.all()`/`.exists()`, `conflicts()`, `scope.exists()`, `[*]`
 * wildcards) fail to parse here too — callers should treat a parse failure on that syntax as
 * "extended form, not locally validated" rather than a hard error (mirrors the Java fallback).
 */

export class ExpressionSyntaxError extends Error {}

type Token = { type: "NUM" | "STR" | "IDENT" | "OP" | "LP" | "RP" | "EOF"; text: string };

const TWO_CHAR_OPS = new Set(["==", "!=", "<=", ">=", "&&", "||"]);
const BOOLEAN_OPS = new Set(["&&", "||", "==", "!=", "<", "<=", ">", ">="]);

function tokenize(expr: string): Token[] {
  const tokens: Token[] = [];
  const s = expr ?? "";
  let i = 0;
  const n = s.length;
  while (i < n) {
    const c = s[i];
    if (/\s/.test(c)) {
      i++;
      continue;
    }
    if (/[0-9]/.test(c) || (c === "." && i + 1 < n && /[0-9]/.test(s[i + 1]))) {
      const start = i;
      while (i < n && /[0-9.]/.test(s[i])) i++;
      tokens.push({ type: "NUM", text: s.slice(start, i) });
      continue;
    }
    if (c === "'" || c === '"') {
      const quote = c;
      let j = i + 1;
      while (j < n && s[j] !== quote) j++;
      if (j >= n) throw new ExpressionSyntaxError("unterminated string literal");
      tokens.push({ type: "STR", text: s.slice(i + 1, j) });
      i = j + 1;
      continue;
    }
    if (/[A-Za-z_]/.test(c)) {
      const start = i;
      while (i < n && /[A-Za-z0-9_]/.test(s[i])) i++;
      while (i < n && s[i] === "." && i + 1 < n && /[A-Za-z_]/.test(s[i + 1])) {
        i++;
        while (i < n && /[A-Za-z0-9_]/.test(s[i])) i++;
      }
      tokens.push({ type: "IDENT", text: s.slice(start, i) });
      continue;
    }
    const two = i + 1 < n ? s.slice(i, i + 2) : "";
    if (TWO_CHAR_OPS.has(two)) {
      tokens.push({ type: "OP", text: two });
      i += 2;
      continue;
    }
    if ("+-*/%<>!()".includes(c)) {
      tokens.push({ type: c === "(" ? "LP" : c === ")" ? "RP" : "OP", text: c });
      i++;
      continue;
    }
    throw new ExpressionSyntaxError(`unexpected character '${c}' in expression`);
  }
  tokens.push({ type: "EOF", text: "" });
  return tokens;
}

export type ExprNode =
  | { kind: "literal"; value: number | string | boolean | null }
  | { kind: "var"; name: string }
  | { kind: "unary"; op: string; operand: ExprNode }
  | { kind: "binary"; op: string; left: ExprNode; right: ExprNode };

class Parser {
  private pos = 0;
  constructor(private tokens: Token[]) {}

  parseAll(): ExprNode {
    const node = this.parseOr();
    this.expect("EOF");
    return node;
  }

  private peek(): Token {
    return this.tokens[this.pos];
  }
  private next(): Token {
    return this.tokens[this.pos++];
  }
  private matchOp(op: string): boolean {
    if (this.peek().type === "OP" && this.peek().text === op) {
      this.pos++;
      return true;
    }
    return false;
  }
  private expect(type: Token["type"]) {
    if (this.peek().type !== type) {
      throw new ExpressionSyntaxError(`expected ${type} but found '${this.peek().text}'`);
    }
    this.pos++;
  }

  private parseOr(): ExprNode {
    let left = this.parseAnd();
    while (this.matchOp("||")) left = { kind: "binary", op: "||", left, right: this.parseAnd() };
    return left;
  }
  private parseAnd(): ExprNode {
    let left = this.parseEquality();
    while (this.matchOp("&&")) left = { kind: "binary", op: "&&", left, right: this.parseEquality() };
    return left;
  }
  private parseEquality(): ExprNode {
    let left = this.parseRelational();
    while (this.peek().type === "OP" && (this.peek().text === "==" || this.peek().text === "!=")) {
      const op = this.next().text;
      left = { kind: "binary", op, left, right: this.parseRelational() };
    }
    return left;
  }
  private parseRelational(): ExprNode {
    let left = this.parseAdditive();
    while (
      this.peek().type === "OP" &&
      ["<", "<=", ">", ">="].includes(this.peek().text)
    ) {
      const op = this.next().text;
      left = { kind: "binary", op, left, right: this.parseAdditive() };
    }
    return left;
  }
  private parseAdditive(): ExprNode {
    let left = this.parseMultiplicative();
    while (this.peek().type === "OP" && (this.peek().text === "+" || this.peek().text === "-")) {
      const op = this.next().text;
      left = { kind: "binary", op, left, right: this.parseMultiplicative() };
    }
    return left;
  }
  private parseMultiplicative(): ExprNode {
    let left = this.parseUnary();
    while (
      this.peek().type === "OP" &&
      ["*", "/", "%"].includes(this.peek().text)
    ) {
      const op = this.next().text;
      left = { kind: "binary", op, left, right: this.parseUnary() };
    }
    return left;
  }
  private parseUnary(): ExprNode {
    if (this.peek().type === "OP" && (this.peek().text === "-" || this.peek().text === "!")) {
      const op = this.next().text;
      return { kind: "unary", op, operand: this.parseUnary() };
    }
    return this.parsePrimary();
  }
  private parsePrimary(): ExprNode {
    const t = this.peek();
    switch (t.type) {
      case "NUM": {
        this.next();
        const value = Number(t.text);
        if (Number.isNaN(value)) throw new ExpressionSyntaxError(`invalid number: ${t.text}`);
        return { kind: "literal", value };
      }
      case "STR":
        this.next();
        return { kind: "literal", value: t.text };
      case "IDENT":
        this.next();
        if (t.text === "true") return { kind: "literal", value: true };
        if (t.text === "false") return { kind: "literal", value: false };
        if (t.text === "null") return { kind: "literal", value: null };
        return { kind: "var", name: t.text };
      case "LP": {
        this.next();
        const inner = this.parseOr();
        this.expect("RP");
        return inner;
      }
      default:
        throw new ExpressionSyntaxError(`unexpected token '${t.text}' in expression`);
    }
  }
}

/** Parse-only syntax check; throws {@link ExpressionSyntaxError} if invalid. */
export function parseExpression(expression: string): ExprNode {
  return new Parser(tokenize(expression)).parseAll();
}

/** True if the top-level operator always yields a boolean (syntactic check, no evaluation). */
export function isBooleanShaped(node: ExprNode): boolean {
  if (node.kind === "literal") return typeof node.value === "boolean";
  if (node.kind === "unary") return node.op === "!";
  if (node.kind === "binary") return BOOLEAN_OPS.has(node.op);
  return false;
}

/** All field/variable names (dotted paths kept whole) referenced anywhere in the expression. */
export function referencedFields(node: ExprNode): Set<string> {
  const out = new Set<string>();
  const walk = (n: ExprNode) => {
    if (n.kind === "var") out.add(n.name);
    else if (n.kind === "unary") walk(n.operand);
    else if (n.kind === "binary") {
      walk(n.left);
      walk(n.right);
    }
  };
  walk(node);
  return out;
}
