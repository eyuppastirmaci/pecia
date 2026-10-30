import { useMemo } from "react";

/** Orders at or above this amount, in cents, ship free with standard delivery. */
export const FREE_SHIPPING_THRESHOLD = 4000;
export const STANDARD_DELIVERY_COST = 490;
export const EXPRESS_DELIVERY_COST = 990;

export type DeliveryOption = "standard" | "express";

/** Returns the delivery cost in cents for a basket subtotal and the chosen option. */
export function useShippingQuote(subtotal: number, option: DeliveryOption): number {
  return useMemo(() => {
    if (option === "express") {
      return EXPRESS_DELIVERY_COST;
    }
    return subtotal >= FREE_SHIPPING_THRESHOLD ? 0 : STANDARD_DELIVERY_COST;
  }, [subtotal, option]);
}
