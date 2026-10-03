"""Sanitized labelled examples based on the user's UPI/card and offer samples.

No real account/card suffixes, references, phone numbers, personal payee names,
or live links belong in source control. These labels describe a completed
outgoing payment, not whether a message merely mentions money.
"""

UPI_PAYMENTS = (
    ("23.00", "BMTC"), ("60.00", "Indiqube Spaces"),
    ("14.00", "DEPOT MANAGER"), ("234.00", "Example Payee A"),
    ("20.00", "Example Payee B"), ("20.00", "Example Payee C"),
    ("20.00", "Example Payee D"), ("23.00", "BMTC"),
    ("70.00", "Indiqube Spaces"), ("23.00", "BMTC"),
    ("65.00", "Example Payee E"), ("14.00", "DEPOT MANAGER"),
)
CARD_PAYMENTS = (
    ("1602.07", "OPENAI *CHATGPT SUBSCR"), ("500", "BANGALORE METRO RAIL"),
    ("399", "Raz*Airtel"), ("1957", "BLINKIT"), ("3060", "Example Clothing Shop"),
    ("807", "BLINKIT"), ("525", "Example Store"), ("392", "SWIGGY PVT LTD FOOD1"),
    ("1999", "OPENAI *CHATGPT SUBSCR"), ("970", "BLINK COMMERCE PVT LTD"),
    ("500", "BANGALORE METRO RAIL"), ("22250", "ASSPL"), ("716", "BLINKIT"),
)

EXPENDITURE_EXAMPLES = tuple(
    f"Sent Rs.{amount}\nFrom HDFC Bank A/C *0000\nTo {merchant}\n"
    "On 01/09/26\nRef DEMO-REFERENCE\nNot You? Contact your bank to block UPI."
    for amount, merchant in UPI_PAYMENTS
) + tuple(
    f"Spent Rs.{amount} On HDFC Bank Card 0000 At {merchant} "
    "On 2026-09-01:08:00:00.Not You? Contact your bank to block the card."
    for amount, merchant in CARD_PAYMENTS
)
OTHER_EXAMPLES = (
    "Good News! Get up to Rs.300 cashback on your next recharge via Airtel app. "
    "Recharge now https://example.invalid/cashback",
    "Offer for DEMO-NUMBER! Recharge now with Rs399 to get unlimited 5G and calls "
    "for 28 days via Airtel App https://example.invalid/recharge",
    "Congratulations! Now you have FREE access to movies and TV shows with your "
    "Airtel recharge. Download Airtel Xstream Play https://example.invalid/movies",
    "You've earned rewards - don't let them go unclaimed. Tap to redeem via "
    "Airtel app https://example.invalid/rewards",
    "Convert spends of Rs.57990 on HDFC Bank Credit Card xx0000 into SmartEMIs "
    "via PayZapp. View EMI plans https://example.invalid/emi T&C",
    "Dear Customer, Xpress Personal Loan of Rs.2.63 Lacs is ready for instant "
    "credit to your HDFC Bank A/c XX0000. Continue https://example.invalid/loan",
    "Exclusive Rates are live! Apply for your Xpress Personal Loan online. "
    "It's fast and hassle-free https://example.invalid/rates -HDFC Bank",
    "Save big on EMI on your HDFC Bank Personal Loan. Get up to Rs.69000 at a "
    "lower rate. Validity: Limited Time https://example.invalid/offer TC",
    "Alert: Your HDFC Bank A/c No.XX0000 gives you an added benefit - a "
    "pre-approved Personal Loan of 69000. Click https://example.invalid/benefit",
)

# A diverse subset goes in the prompt; remaining examples can be evaluated
# separately. Similar bank templates are not a broad independent accuracy set.
PROMPT_EXAMPLES = tuple((EXPENDITURE_EXAMPLES[i], True) for i in (0, 1, 12, 15)) + tuple(
    (OTHER_EXAMPLES[i], False) for i in (0, 1, 4, 5)
) + (
    ("Your OTP is 000000 for a proposed card payment of Rs.500. Do not share it.", False),
    ("Rs.500 credited to your account from Example Employer.", False),
    ("Your Rs.500 UPI payment failed. No money was debited.", False),
)
