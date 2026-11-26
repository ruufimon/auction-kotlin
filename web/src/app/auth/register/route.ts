import type { NextRequest } from "next/server";
import { startLogin } from "@/lib/server/login";

export const dynamic = "force-dynamic";
export function GET(req: NextRequest) {
  return startLogin(req, "register");
}
