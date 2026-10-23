// Tiny typed wrapper around the core API. Types mirror the Java records in core/.
// Money is always in fils (amountMinor): 30000 = AED 300.00. The UI only formats it, never calculates.

export type Role = "PARENT" | "CHILD";
export type RequestStatus = "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED" | "BLOCKED";

export interface Me {
  memberId: string;
  displayName: string;
  role: Role;
  ageBand: string;
  familyId: string;
  familyName: string;
  currency: string;
}

export interface SpendingRequest {
  id: string;
  requesterId: string;
  kind: "SPEND" | "ASK_TO_BUY" | "CHALLENGE" | "REWARD";
  title: string;
  amountMinor: number;
  currency: string;
  category?: string;
  productUrl?: string;
  status: RequestStatus;
  agentSummary?: string;
  createdAt: string;
}

export interface MemberView {
  id: string;
  displayName: string;
  role: Role;
  ageBand: string;
}

const TOKEN_KEY = "ffa.token";

export function getToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(TOKEN_KEY, token);
    else sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    /* private mode: stay logged in for this page only */
  }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const res = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init.headers,
    },
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body.detail ?? `Request failed (${res.status})`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

export const api = {
  login: (username: string, password: string) =>
    call<{ accessToken: string; role: Role }>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  me: () => call<Me>("/api/me"),
  members: () => call<MemberView[]>("/api/family/members"),
  requests: () => call<SpendingRequest[]>("/api/requests"),
  createRequest: (title: string, amountMinor: number, productUrl?: string) =>
    call<SpendingRequest>("/api/requests", {
      method: "POST",
      body: JSON.stringify({ kind: productUrl ? "ASK_TO_BUY" : "SPEND", title, amountMinor, productUrl }),
    }),
  decide: (id: string, decision: "approve" | "reject") =>
    call<SpendingRequest>(`/api/requests/${id}/${decision}`, { method: "POST" }),
};

// The agent service (Python). It calls the core with the same token, so it can't do more than the member can.
export const agent = {
  chat: (message: string) =>
    call<{ agent: string; reply: string }>("/agent/chat", { method: "POST", body: JSON.stringify({ message }) }),
  nudge: () => call<{ nudge: string | null }>("/agent/nudge"),
};

export function formatMoney(minor: number, currency = "AED") {
  return `${currency} ${(minor / 100).toFixed(2)}`;
}
