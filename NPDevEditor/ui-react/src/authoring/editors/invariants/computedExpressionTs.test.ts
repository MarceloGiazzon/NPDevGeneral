import { describe, expect, it } from "vitest";
import {
  ExpressionSyntaxError,
  isBooleanShaped,
  parseExpression,
  referencedFields
} from "./computedExpressionTs";

describe("computedExpressionTs (LIFT-EXPR-P5 client mirror)", () => {
  it("parses parens/!/arithmetic/comparison combos", () => {
    expect(() => parseExpression("(a > b && c != null) || !flag")).not.toThrow();
    expect(() => parseExpression("total == pos*cxPad + cxAvulsas")).not.toThrow();
  });

  it("flags boolean-shape correctly, mirroring the server", () => {
    expect(isBooleanShaped(parseExpression("a > b"))).toBe(true);
    expect(isBooleanShaped(parseExpression("!flag"))).toBe(true);
    expect(isBooleanShaped(parseExpression("(a > b) || (c < d)"))).toBe(true);
    expect(isBooleanShaped(parseExpression("a + b"))).toBe(false);
    expect(isBooleanShaped(parseExpression("a"))).toBe(false);
  });

  it("collects dotted and plain field references", () => {
    const fields = referencedFields(parseExpression("(cliente.tipo == 'PJ' && total > 0) || a"));
    expect(fields).toEqual(new Set(["cliente.tipo", "total", "a"]));
  });

  it("throws ExpressionSyntaxError on CEL-specific / malformed syntax", () => {
    expect(() => parseExpression("scope.exists(\"Other\", \"id\", pos)")).toThrow(ExpressionSyntaxError);
    expect(() => parseExpression("allergies.uniqueBy(allergen)")).toThrow(ExpressionSyntaxError);
    expect(() => parseExpression("pos * ")).toThrow(ExpressionSyntaxError);
    expect(() => parseExpression("(pos + 1")).toThrow(ExpressionSyntaxError);
  });
});
