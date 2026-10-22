import { Navigate, Route, Routes } from 'react-router-dom';
import { Layout } from './components/Layout';
import { useSession } from './session';
import { Dashboard } from './pages/Dashboard';
import { Ledger } from './pages/Ledger';
import { NewOrder } from './pages/NewOrder';
import { OrderDetail } from './pages/OrderDetail';
import { Orders } from './pages/Orders';
import { PaymentDetail } from './pages/PaymentDetail';
import { Payments } from './pages/Payments';
import { Reconciliation } from './pages/Reconciliation';
import { WebhookDeliveryDetail } from './pages/WebhookDeliveryDetail';
import { Webhooks } from './pages/Webhooks';
import { Welcome } from './pages/Welcome';

export function App() {
  const { apiKey } = useSession();
  if (!apiKey) return <Welcome />;

  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="payments" element={<Payments />} />
        <Route path="payments/:paymentId" element={<PaymentDetail />} />
        <Route path="orders" element={<Orders />} />
        <Route path="orders/new" element={<NewOrder />} />
        <Route path="orders/:orderId" element={<OrderDetail />} />
        <Route path="ledger" element={<Ledger />} />
        <Route path="webhooks" element={<Webhooks />} />
        <Route path="webhooks/deliveries/:deliveryId" element={<WebhookDeliveryDetail />} />
        <Route path="reconciliation" element={<Reconciliation />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}
