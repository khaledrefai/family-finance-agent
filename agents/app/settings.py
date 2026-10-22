"""Settings read from environment variables (see .env.example at the repo root)."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENTS_")

    # Where the Spring Boot core API lives. The agent service talks to the database ONLY through it.
    core_url: str = "http://localhost:8080"


settings = Settings()
