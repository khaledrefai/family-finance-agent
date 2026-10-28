"""Settings read from environment variables (see .env.example at the repo root)."""

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENTS_")

    # Where the Spring Boot core API lives. The agent service talks to the database ONLY through it.
    core_url: str = "http://localhost:8080"

    # The LLM step. "off" = always the rule-based fallback (demo-safe, no key needed),
    # "on" = always try Claude, "auto" = try Claude only when an API key is set.
    llm: str = "auto"
    llm_model: str = "claude-opus-5-5"
    llm_timeout_s: float = 6.0
    # Reading a receipt photo or a PDF takes Claude far longer than a chat reply
    llm_read_timeout_s: float = 40.0
    anthropic_api_key: str | None = Field(default=None, validation_alias="ANTHROPIC_API_KEY")


settings = Settings()
