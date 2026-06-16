from fastapi import FastAPI, Request

app = FastAPI()

@app.post("/webhook")
async def whatsapp_webhook(request: Request):

    data = await request.form()

    message = data.get("Body")
    sender = data.get("From")

    print("\n===== NEW MESSAGE =====")
    print("Sender:", sender)
    print("Message:", message)

    return "OK"
