import { useState } from "react";
import { createOrder } from "../api/client";
import { isEnabled } from "../flags";
import { FREE_SHIPPING_THRESHOLD, DeliveryOption, useShippingQuote } from "./useShippingQuote";

export function CheckoutForm({ subtotal }: { subtotal: number }) {
  const [option, setOption] = useState<DeliveryOption>("standard");
  const delivery = useShippingQuote(subtotal, option);
  const missing = FREE_SHIPPING_THRESHOLD - subtotal;

  return (
    <form onSubmit={() => createOrder({ delivery: option })}>
      {isEnabled("one-page-checkout") && <p>Pay and confirm on one page.</p>}
      {missing > 0 && <p>Add {missing / 100} euros more for free delivery.</p>}
      <select value={option} onChange={(event) => setOption(event.target.value as DeliveryOption)}>
        <option value="standard">Standard</option>
        <option value="express">Express</option>
      </select>
      <p>Delivery: {delivery / 100} euros</p>
      <button type="submit">Place order</button>
    </form>
  );
}
