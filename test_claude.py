import os
from dotenv import load_dotenv
from anthropic import Anthropic

load_dotenv()

client = Anthropic(
    api_key=os.getenv("ANTHROPIC_API_KEY")
)

message = client.messages.create(
    model="claude-sonnet-4-6",
    max_tokens=500,
    messages=[
        {
            "role": "user",
            "content": """
			Extract expenses from:

			Lunch 55
			Bus 23

			Return ONLY JSON.
			"""
        }
    ]
)

print(message.content[0].text)
