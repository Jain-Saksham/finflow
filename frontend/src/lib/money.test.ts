import { describe, expect, it } from 'vitest';
import { formatMoney, minorToDecimalString, parseAmountToMinor } from './money';

describe('minorToDecimalString', () => {
  it('converts minor units without floating point', () => {
    expect(minorToDecimalString(123456)).toBe('1234.56');
    expect(minorToDecimalString(5)).toBe('0.05');
    expect(minorToDecimalString(0)).toBe('0.00');
    expect(minorToDecimalString(-250)).toBe('-2.50');
  });

  it('rejects non-integers', () => {
    expect(() => minorToDecimalString(1.5)).toThrow();
  });
});

describe('parseAmountToMinor', () => {
  it.each([
    ['12', 1200],
    ['12.5', 1250],
    ['12.05', 1205],
    ['0.10', 10],
    ['1,234.56', 123456],
    ['  7.00 ', 700],
  ])('parses %s', (input, expected) => {
    expect(parseAmountToMinor(input)).toBe(expected);
  });

  it.each(['', 'abc', '1.234', '-5', '0', '0.00', '1e3', '10000000.01', '12.'])('rejects %s', (input) => {
    expect(parseAmountToMinor(input)).toBeNull();
  });

  it('avoids the classic 0.1 + 0.2 floating point error', () => {
    expect(parseAmountToMinor('0.1')! + parseAmountToMinor('0.2')!).toBe(30);
  });
});

describe('formatMoney', () => {
  it('formats with currency', () => {
    expect(formatMoney(123456, 'USD', 'en-US')).toBe('$1,234.56');
    expect(formatMoney(5, 'EUR', 'en-US')).toBe('€0.05');
  });
});
