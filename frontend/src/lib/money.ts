// Money stays an integer number of minor units end to end. These helpers convert to and from
// display strings with string arithmetic, so no floating-point value ever represents money.
// All supported currencies (USD, EUR, GBP, INR) use two minor-unit digits.

const MINOR_DIGITS = 2;
const MAX_MINOR = 100_000_000; // mirrors the backend limit (1,000,000.00)

/** 123456 -> "1234.56"; -5 -> "-0.05" */
export function minorToDecimalString(minor: number): string {
  if (!Number.isSafeInteger(minor)) throw new Error(`Not an integer amount: ${minor}`);
  const sign = minor < 0 ? '-' : '';
  const digits = Math.abs(minor).toString().padStart(MINOR_DIGITS + 1, '0');
  return `${sign}${digits.slice(0, -MINOR_DIGITS)}.${digits.slice(-MINOR_DIGITS)}`;
}

/** Formats minor units with the currency symbol; Intl accepts the exact decimal string. */
export function formatMoney(minor: number, currency: string, locale?: string): string {
  const formatter = new Intl.NumberFormat(locale, { style: 'currency', currency });
  return formatter.format(minorToDecimalString(minor) as Intl.StringNumericLiteral);
}

/**
 * Parses user input such as "12", "12.5", "1,234.56" into minor units.
 * Returns null for anything that is not a positive amount with at most two decimals.
 */
export function parseAmountToMinor(input: string): number | null {
  const cleaned = input.trim().replace(/,/g, '');
  const match = /^(\d{1,7})(?:\.(\d{1,2}))?$/.exec(cleaned);
  if (!match) return null;
  const whole = Number.parseInt(match[1], 10);
  const fraction = Number.parseInt((match[2] ?? '').padEnd(MINOR_DIGITS, '0'), 10);
  const minor = whole * 100 + fraction;
  return minor > 0 && minor <= MAX_MINOR ? minor : null;
}
