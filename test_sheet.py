import gspread
from google.oauth2.service_account import Credentials

SCOPES = [
    "https://www.googleapis.com/auth/spreadsheets",
    "https://www.googleapis.com/auth/drive"
]

creds = Credentials.from_service_account_file(
    "service-account.json",
    scopes=SCOPES
)

client = gspread.authorize(creds)

sheet = client.open("Expense_Tracker").sheet1

sheet.append_row([
    "2026-06-12",
    "Transport",
    "Bus",
    23
])

print("Success")