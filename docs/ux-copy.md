# UX copy guide

How the app talks. Check new strings against this before they ship, in the web app, the agent replies and the core's error `detail`s (those reach the screen as written).

## Voice

- **Parents:** calm, factual, short. Lead with the number or the decision. Never tell a parent what to do with their child; "Worth a chat" is as far as we go.
- **Children (9 and 13):** warm, plain, no lectures. Thank honesty, never scold. Examples use real UAE things ("12 dirhams shawarma", "8 dirhams chicken wrap").
- **Both:** whole sentences, no jargon ("money engine", "request kind", enum names), no exclamation marks, no emoji.

## Words we use

| Use | Not | Where |
|---|---|---|
| Approve / Decline | Accept, Reject, Deny | Parent buttons and stamps. The API says `reject`; the screen never does. |
| Waiting | Pending | Stamp and "Waiting for a decision" |
| Blocked by a family rule | Denied, Not allowed | Stamp when a rule stops a request |
| Ask for something / request | Submit, ticket | Child form and "My requests" |
| Send to a parent | Submit | Child form button |
| AED in amounts, "dirhams" in examples | Dhs, AED in chat examples | Amounts always come from the core's `text`; kids type the way they talk |
| your parents / a parent | guardian, admin | Child-facing text |
| the kids | children, dependants | Parent-facing text ("Ask about the kids' money") |

## Patterns

- **Errors:** what happened, then how to fix it, in one or two sentences. End with a full stop. No status codes, no "Failed to fetch". The web client replaces network failures and 5xx with fixed lines (`web/lib/api.ts`), so core `detail`s only need to cover 4xx cases.
- **Core `detail` strings:** a plain sentence with no trailing full stop (the agent adds one when it wraps them: "I couldn't do that. {detail}.").
- **Empty states:** what will appear here and what makes it appear. "Nothing waiting. When one of the kids asks for something, it lands here."
- **Success:** say it happened and where to look next. "Sent. Your parents can see … now, and their answer will show under My requests."
- **Loading:** "Loading…" for pages, "Working it out…" while an agent answers.
- **Agents not built yet:** say what isn't ready and what it will do. No week numbers or roadmap talk; these lines can appear in the demo video.
- **Trust lines:** say where numbers come from in words a parent understands: "Totals are worked out exactly by the app, not guessed by the AI."

## Arabic later (verify before translating)

- Arabic text runs about 20 to 30% shorter in characters but needs more line height; keep buttons from depending on width.
- Keep the AED figure as a separate element (`Amount` in `chit.tsx`) so it can sit correctly in right-to-left layout.
- "Worth a chat" and "lands here" are idioms; translators should render the meaning ("worth talking about", "appears here"), not the words.
