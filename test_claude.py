import os, json
from dotenv import load_dotenv
from anthropic import Anthropic
from datetime import datetime

load_dotenv()

client = Anthropic(
    api_key=os.getenv("ANTHROPIC_API_KEY")
)

today = datetime.now().strftime("%Y-%m-%d")

message = client.messages.create(
    model="claude-sonnet-4-6",
    max_tokens=500,
    messages=[
    {
        "role": "user",
        "content": f"""
        You are an expense extraction system.

        Today's date is {today}.

        Valid categories:
        - Food
        - Transport
        - Groceries
        - Family
        - Gifts
        - Utilities
        - Clothing
        - Investments
        - Home & Kitchen
        - Family & Leisure
        - Family & Celebration
        - Snacks
        - Other

        Return ONLY valid JSON.

        Schema:
        {{
        "expenses": [
            {{
            "date": "YYYY-MM-DD",
            "category": "Category Name",
            "event": "Description",
            "price": 0
            }}
        ]
        }}

        Rules:
        - Extract all expenses mentioned in the message.
        - One expense entry per purchase/spend event.
        - The date field is mandatory.
        - Convert all dates to ISO format YYYY-MM-DD.
        - Resolve relative dates such as:
        - today
        - yesterday
        - day before yesterday
        - last Monday
        - last Sunday
        - last week
        - If no date is mentioned, use today's date.
        - Price must be numeric without currency symbols.
        - Choose the most appropriate category from the valid categories list.
        - Preserve important event details.
        - Return JSON only.
        - Do not include markdown, explanations, or additional text.

        Message:
        I had an Ice-cream date with my little sister today. I spent Rs. 200.
        Oh, I forgot to mention, I bought my dad a birthday cake last Sunday for Rs. 150.
        """
    }
    ]
)

response_text = message.content[0].text

data = json.loads(response_text)

print(json.dumps(data, indent=2))
