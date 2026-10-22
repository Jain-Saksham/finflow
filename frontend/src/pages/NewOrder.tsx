import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';
import { useCreateOrder } from '../api/hooks';
import { CURRENCIES, type Currency } from '../api/types';
import { ErrorAlert } from '../components/States';
import { parseAmountToMinor } from '../lib/money';

function suggestReference(): string {
  return `ORD-${Math.random().toString(36).slice(2, 8).toUpperCase()}`;
}

export function NewOrder() {
  const navigate = useNavigate();
  const createOrder = useCreateOrder();
  const [reference, setReference] = useState(suggestReference);
  const [amount, setAmount] = useState('49.90');
  const [currency, setCurrency] = useState<Currency>('USD');
  const [description, setDescription] = useState('');
  const [clientErrors, setClientErrors] = useState<Record<string, string>>({});

  const serverError = createOrder.error instanceof ApiError ? createOrder.error : null;
  const fieldError = (field: string) => clientErrors[field] ?? serverError?.fieldError(field);

  function submit(e: FormEvent) {
    e.preventDefault();
    const errors: Record<string, string> = {};
    const amountMinor = parseAmountToMinor(amount);
    if (amountMinor === null) errors.amountMinor = 'Enter an amount between 0.01 and 1,000,000.00 with at most two decimals.';
    if (!/^[A-Za-z0-9._-]{1,64}$/.test(reference.trim())) errors.merchantReference = "Use letters, digits, '.', '_' or '-' (max 64).";
    setClientErrors(errors);
    if (Object.keys(errors).length > 0 || amountMinor === null) return;

    createOrder.mutate(
      { merchantReference: reference.trim(), amountMinor, currency, description: description.trim() || undefined },
      { onSuccess: (order) => navigate(`/orders/${order.id}`, { state: { created: true } }) },
    );
  }

  return (
    <>
      <div className="page-head">
        <div>
          <div className="crumbs"><Link to="/orders">Orders</Link> / New</div>
          <h1>New order</h1>
          <p>Create an order, then run a simulated payment for it on the next screen.</p>
        </div>
      </div>
      <form className="card" onSubmit={submit} noValidate style={{ maxWidth: 720 }}>
        <div className="card-body stack">
          <div className="form-grid">
            <div className="field">
              <label htmlFor="reference">Merchant reference</label>
              <input id="reference" value={reference} maxLength={64} onChange={(e) => setReference(e.target.value)}
                aria-invalid={fieldError('merchantReference') ? 'true' : undefined} />
              {fieldError('merchantReference')
                ? <span className="error">{fieldError('merchantReference')}</span>
                : <span className="hint">Your own order number. Must be unique.</span>}
            </div>
            <div className="field">
              <label htmlFor="amount">Amount</label>
              <div className="input-affix">
                <span>{currency}</span>
                <input id="amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
                  aria-invalid={fieldError('amountMinor') ? 'true' : undefined} />
              </div>
              {fieldError('amountMinor')
                ? <span className="error">{fieldError('amountMinor')}</span>
                : <span className="hint">Sent to the API as integer minor units (cents).</span>}
            </div>
            <div className="field">
              <label htmlFor="currency">Currency</label>
              <select id="currency" value={currency} onChange={(e) => setCurrency(e.target.value as Currency)}>
                {CURRENCIES.map((c) => <option key={c}>{c}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="description">Description (optional)</label>
              <input id="description" maxLength={255} value={description} onChange={(e) => setDescription(e.target.value)}
                placeholder="Espresso beans 1kg" />
            </div>
          </div>
          {createOrder.error && !serverError?.errors.length && (
            <ErrorAlert error={createOrder.error} title="Could not create the order" />
          )}
          <div className="row">
            <button className="btn btn-primary" disabled={createOrder.isPending}>
              {createOrder.isPending && <span className="spinner" />} Create order
            </button>
            <Link className="btn" to="/orders">Cancel</Link>
          </div>
        </div>
      </form>
    </>
  );
}
