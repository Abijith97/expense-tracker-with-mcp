"""Starter category vocabulary and synthetic few-shot examples, not training.

These are initial POC conventions, not user-confirmed labels. Personal names,
general marketplaces, and mixed purchases deliberately illustrate uncertainty.
"""

CATEGORIES = (
    "Transport", "Groceries", "Food & dining", "Bills & utilities",
    "Subscriptions", "Shopping", "Health", "Education", "Entertainment",
    "Housing", "Personal care", "Other", "Not sure",
)

PROMPT_EXAMPLES = (
    ("Sent Rs.23 From HDFC Bank A/C *0000 To BMTC On 01/09/26 Ref DEMO", "Transport"),
    ("Spent Rs.500 on card *0000 at BANGALORE METRO RAIL C. Ref DEMO", "Transport"),
    ("Spent Rs.807 on card *0000 at BLINKIT. Ref DEMO", "Groceries"),
    ("Spent Rs.392 on card *0000 at SWIGGY PVT LTD FOOD1. Ref DEMO", "Food & dining"),
    ("Spent Rs.399 on card *0000 at Raz*Airtel for mobile recharge. Ref DEMO", "Bills & utilities"),
    ("Spent Rs.1999 on card *0000 at OPENAI *CHATGPT SUBSCR. Ref DEMO", "Subscriptions"),
    ("Paid Rs.800 for a shirt at Example Clothing Shop. Ref DEMO", "Shopping"),
    ("Paid Rs.250 for medicines at Example Pharmacy. Ref DEMO", "Health"),
    ("Paid Rs.500 for a programming course. Ref DEMO", "Education"),
    ("Paid Rs.300 for movie tickets at Example Cinema. Ref DEMO", "Entertainment"),
    ("Paid Rs.10000 apartment rent to Example Landlord. Ref DEMO", "Housing"),
    ("Paid Rs.200 for a haircut at Example Salon. Ref DEMO", "Personal care"),
    ("Paid Rs.100 as a charitable donation. Ref DEMO", "Other"),
    ("Sent Rs.234 to Example Person. Ref DEMO", "Not sure"),
    ("Spent Rs.22250 on card *0000 at ASSPL. Ref DEMO", "Not sure"),
    ("Paid Rs.700 for groceries and a mobile recharge in one transaction. Ref DEMO", "Not sure"),
)
