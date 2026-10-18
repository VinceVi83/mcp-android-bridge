import base64
import os
import tempfile
from starlette.applications import Starlette
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import Response
from starlette.routing import Route
import uvicorn

from fastmcp import FastMCP
from common.conf_manager import cfg, Utils, setup_logging
import logging

setup_logging()
logger = logging.getLogger(__name__)

mcp = FastMCP("AndroidMCPServer")

def _save_temp_audio(audio_base64: str, format_ext: str = "m4a") -> str:
    audio_bytes = base64.b64decode(audio_base64)
    tmp = tempfile.NamedTemporaryFile(suffix=f".{format_ext}", delete=False)
    tmp.write(audio_bytes)
    tmp.close()
    return tmp.name

@mcp.tool()
def tool_correct(text: str) -> str:
    return f"[CORRECTED] {text}"

@mcp.tool()
def tool_translate(text: str, target_lang: str = "en") -> str:
    return f"[{target_lang.upper()}] {text}"

@mcp.tool()
def tool_check_info(text: str) -> str:
    return f"[INFO RESULT] {text}"

@mcp.tool()
def tool_check_page(text: str) -> str:
    return f"[PAGE CHECK RESULT] {text}"

@mcp.tool()
def tool_stt(audio_base64: str, format_ext: str = "m4a") -> str:
    tmp_path = _save_temp_audio(audio_base64, format_ext)
    print(f"Transcribing audio {tmp_path} template")
    try:
        result = "Transcribed text template"
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
    return result

@mcp.tool()
def tool_dicte_translate(
    audio_base64: str, target_lang: str = "en", format_ext: str = "m4a"
) -> str:
    tmp_path = _save_temp_audio(audio_base64, format_ext)
    print(f"Transcribing audio {tmp_path} template")
    try:
        result = (
            f"[{target_lang.upper()}] Translated audio transcription template"
        )
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)
    return result

@mcp.tool()
def tool_ask_mcp(
    text: str = "", audio_base64: str = "", format_ext: str = "m4a"
) -> str:
    if text:
        query = text
        print(f"Handling command: {query}")
    elif audio_base64:
        tmp_path = _save_temp_audio(audio_base64, format_ext)
        print(f"Transcribing audio {tmp_path} template")
        try:
            query = "Transcribed prompt from audio template"
        finally:
            print('OK')
            # if os.path.exists(tmp_path):
            #     os.remove(tmp_path)
    else:
        query = ""
    return f"[MCP RESPONSE] Handled query: {query}"

@mcp.tool()
def tool_ask_multiroom(
    text: str = "", audio_base64: str = "", format_ext: str = "m4a"
) -> str:
    if text:
        cmd = text
        print(f"Handling command: {cmd}")
    elif audio_base64:
        tmp_path = _save_temp_audio(audio_base64, format_ext)
        print(f"Transcribing audio {tmp_path} template")
        try:
            cmd = "Transcribed multiroom audio command"
        finally:
            print('OK')
            # if os.path.exists(tmp_path):
            #     os.remove(tmp_path)
    else:
        cmd = ""
    return "OK"

class AuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.headers.get("X-Auth-Token") != cfg.conf.token:
            return Response("Unauthorized", status_code=401)
        return await call_next(request)

async def route_call(request: Request) -> Response:
    try:
        payload = await request.json()
    except Exception:
        return Response("Invalid JSON", status_code=400)

    tool_name = payload.get("tool", "")
    args = payload.get("args", {})

    if not tool_name.startswith("tool_"):
        return Response("Tool not found", status_code=404)

    func = globals().get(tool_name)
    if not callable(func):
        return Response("Tool not found", status_code=404)

    try:
        res = func(**args)
        return Response(str(res), media_type="text/plain")
    except Exception as e:
        return Response(str(e), status_code=500)

routes = [
    Route("/call", endpoint=route_call, methods=["POST"]),
]

app = Starlette(routes=routes)
app.add_middleware(AuthMiddleware)

if __name__ == "__main__":
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=cfg.conf.port,
        ssl_certfile=cfg.config_dir.joinpath("cert.pem"),
        ssl_keyfile=cfg.config_dir.joinpath("key.pem"),
    )
