"""Local capability information for the current help-only Chat tab."""


def get_help() -> str:
    """Explain setup without credentials, inference, or expense writes."""
    return (
        "Your message reached the phone-side Python orchestrator.\n\n"
        "This build provides built-in help and captures new SMS after you enable "
        "access in the SMS capture tab. Captured messages get a Python "
        "acknowledgement. Enable cloud classification in SMS capture with your "
        "own OpenAI API key and consent to classify new messages as Expenditure "
        "or Other. The Python classifier builds the prompt and calls OpenAI "
        "directly. Classification uploads the SMS body and may incur API charges.\n\n"
        "Category confirmation, recording expenses, and spending summaries are "
        "planned. They are not available yet. For now, every chat message "
        "returns this help information."
    )
