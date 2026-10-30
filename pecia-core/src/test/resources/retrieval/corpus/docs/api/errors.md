# Error codes

Every API error has a stable code. Clients should branch on the code, never on the message text.

| Code | Meaning | What the customer sees |
| --- | --- | --- |
| E1001 | The basket is empty. | "Your basket is empty." |
| E1017 | A book in the basket is no longer available. | The line is highlighted and checkout is blocked. |
| E1042 | The card was declined by the bank. | "Your bank declined the payment. Please try another card." |
| E1043 | The payment provider timed out. | "We could not reach the payment provider. You have not been charged." |
| E2005 | The delivery address cannot be served. | The address form asks for another address. |
