"""Personal few-shot labels supplied by the user; this is prompting, not training.

All 24 examples retain only the payee/merchant signal needed for the preference.
Account/card suffixes, transaction references, dates/times and bank contact details
are replaced/omitted. Payee names remain personal preferences for this one-user POC.
They do not prove that the same payee always has the same purpose for anyone else.
"""

CATEGORY_HIERARCHY = {
    "learning": ("engineering",),
    "utilities": ("transport", "food", "prepaid recharge", "groceries", "health"),
    "fun": ("gift", "food"),
    "Not sure": ("Not sure",),
}
CATEGORIES = tuple(CATEGORY_HIERARCHY)
SUB_CATEGORIES = tuple(dict.fromkeys(
    sub_category for values in CATEGORY_HIERARCHY.values() for sub_category in values
))
CATEGORY_PAIRS = tuple(
    (category, sub_category)
    for category, values in CATEGORY_HIERARCHY.items()
    for sub_category in values
)


def is_valid_label(value):
    """Validate both fields and their relationship, not just separate enums."""
    return (
        type(value) is dict
        and set(value) == {"category", "sub_category"}
        and type(value["category"]) is str
        and type(value["sub_category"]) is str
        and value["sub_category"] in CATEGORY_HIERARCHY.get(value["category"], ())
    )


# Order follows the user's 24 examples. Values are synthetic; purpose is never
# inferred from an amount. Repeated merchants demonstrate consistent labels.
PERSONAL_EXAMPLES = (
    ("Spent Rs.100 on HDFC Bank Card *0000 at OPENAI. Ref DEMO", "learning", "engineering"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at BANGALORE METRO RAIL C. Ref DEMO", "utilities", "transport"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at RAZ*Toyloft. Ref DEMO", "fun", "gift"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To DEPOT MANAGER DODDABALLAP. Ref DEMO", "utilities", "transport"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To Indiqube SpacesExternal S. Ref DEMO", "utilities", "food"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at OPENAI *CHATGPT SUBSCR. Ref DEMO", "learning", "engineering"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To ALEEM PASHA. Ref DEMO", "utilities", "transport"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at Raz*Airtel. Ref DEMO", "utilities", "prepaid recharge"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To Javed Khan. Ref DEMO", "utilities", "transport"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at BLINKIT. Ref DEMO", "utilities", "groceries"),
    ("Sent Rs.15 From HDFC Bank A/C *0000 To DEPOT MANAGER DODDABALLAP. Ref DEMO", "utilities", "transport"),
    ("Sent Rs.15 From HDFC Bank A/C *0000 To Indiqube SpacesExternal S. Ref DEMO", "utilities", "food"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To BMTC. Ref DEMO", "utilities", "transport"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To Chethan B S. Ref DEMO", "utilities", "food"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at SWIGGY PVT LTD FOOD1. Ref DEMO", "fun", "food"),
    ("Spent Rs.150 on HDFC Bank Card *0000 at BLINKIT. Ref DEMO", "utilities", "groceries"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To AJAS. Ref DEMO", "utilities", "transport"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To Garden city chicken centr. Ref DEMO", "utilities", "groceries"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To CHANDRASHEKAR. Ref DEMO", "utilities", "transport"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at BLINK COMMERCE PVT LTD. Ref DEMO", "utilities", "groceries"),
    ("Spent Rs.150 on HDFC Bank Card *0000 at BANGALORE METRO RAIL C. Ref DEMO", "utilities", "transport"),
    ("Sent Rs.10 From HDFC Bank A/C *0000 To SIVAMMA. Ref DEMO", "utilities", "transport"),
    ("Sent Rs.15 From HDFC Bank A/C *0000 To Garden city chicken centr. Ref DEMO", "utilities", "groceries"),
    ("Spent Rs.100 on HDFC Bank Card *0000 at ..VAMAN CLINIC PHA_. Ref DEMO", "utilities", "health"),
)

PROMPT_EXAMPLES = PERSONAL_EXAMPLES + (
    ("Sent Rs.10 to Unknown Person. Ref DEMO", "Not sure", "Not sure"),
    ("Spent Rs.100 on card *0000 at ASSPL. Ref DEMO", "Not sure", "Not sure"),
    ("Paid Rs.100 for groceries and a mobile recharge in one transaction. Ref DEMO", "Not sure", "Not sure"),
    ("Spent Rs.100 at BLINKIT for medicines. Ref DEMO", "utilities", "health"),
    ("Spent Rs.100 at SWIGGY PVT LTD FOOD1 for groceries. Ref DEMO", "utilities", "groceries"),
)
