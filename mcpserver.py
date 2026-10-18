import base64
import ipaddress
import logging
import os
import tempfile
import uuid
import uvicorn
import asyncio
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import FileResponse, Response
from starlette.exceptions import HTTPException
from fastmcp import FastMCP

from common.llm_client import llm
from common.conf_manager import cfg, Utils, setup_logging

setup_logging()
logger = logging.getLogger(__name__)

mcp = FastMCP('AndroidMCPServer')

AUDIO_DIR = "/tmp/mcp_audio"
os.makedirs(AUDIO_DIR, exist_ok=True)
ALLOWED_NETWORK = ipaddress.ip_network("192.168.0.0/24")

def verify_local_ip(request: Request):
    client_ip_str = request.client.host if request.client else None
    if not client_ip_str:
        raise HTTPException(status_code=403, detail="Forbidden: Unable to determine client IP")

    try:
        client_ip = ipaddress.ip_address(client_ip_str)
    except ValueError:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid client IP")

    if client_ip not in ALLOWED_NETWORK and not client_ip.is_loopback:
        raise HTTPException(status_code=403, detail="Forbidden: Local IP only")

@mcp.custom_route("/audio/{filename}", methods=["GET"])
async def get_audio_file(request: Request):
    verify_local_ip(request)
    filename = request.path_params.get("filename", "")
    safe_filename = os.path.basename(filename)
    file_path = os.path.join(AUDIO_DIR, safe_filename)
    if not os.path.isfile(file_path):
        raise HTTPException(status_code=404, detail="File not found")
    return FileResponse(file_path, media_type="audio/m4a")

def _save_temp_audio(audio_base64: str, format_ext: str = "m4a") -> tuple[str, str]:
    audio_bytes = base64.b64decode(audio_base64)
    filename = f"{uuid.uuid4().hex}.{format_ext}"
    file_path = os.path.join(AUDIO_DIR, filename)
    with open(file_path, "wb") as f:
        f.write(audio_bytes)

    url = f"https://{Utils.get_local_ip()}:{cfg.conf.port}/audio/{filename}"
    return url, file_path

@mcp.tool()
def tool_correct(text: str) -> str:
    res = llm.call(cfg.agents.corrector, text, model=cfg.llm_models.creative, mode='summarize')
    text = Utils.extract_llm_response(res)
    return f'{text}'

@mcp.tool()
def tool_translate(text: str, target_lang: str = 'en') -> str:
    res = llm.call(cfg.agents.translator, text, model=cfg.llm_models.creative, mode='summarize')
    text = Utils.extract_llm_response(res)
    return f'{text}'

@mcp.tool()
def tool_check_info(text: str) -> str:
    return f'[INFO RESULT] {text}'

@mcp.tool()
def tool_check_page(text: str) -> str:
    return f'[PAGE CHECK RESULT] {text}'

@mcp.tool()
def tool_stt(audio_base64: str, format_ext: str = 'm4a') -> str:
    url, file_path = _save_temp_audio(audio_base64, format_ext)
    text = 'tool_stt failed'
    try:
        res = llm.transcribe(url)
        text = Utils.extract_llm_response(res)
    finally:
        if os.path.exists(file_path):
            os.remove(file_path)
    return text

@mcp.tool()
def tool_dicte_translate(audio_base64: str, target_lang: str = 'en', format_ext: str = 'm4a') -> str:
    url, file_path = _save_temp_audio(audio_base64, format_ext)
    text = 'tool_dicte_translate failed'
    try:
        res = llm.transcribe(url)
        text = Utils.extract_llm_response(res)
        res = llm.call(cfg.agents.translator, text, model=cfg.llm_models.creative, mode='summarize')
        text = Utils.extract_llm_response(res)
    finally:
        if os.path.exists(file_path):
            os.remove(file_path)
    return text

@mcp.tool()
def tool_ask_mcp(text: str = '', audio_base64: str = '', format_ext: str = 'm4a') -> str:
    if text:
        query = text
    elif audio_base64:
        url, file_path = _save_temp_audio(audio_base64, format_ext)
        try:
            res = llm.transcribe(url)
            query = Utils.extract_llm_response(res)
        finally:
            if os.path.exists(file_path):
                os.remove(file_path)
    else:
        query = ''

    Utils.call_ask(query)
    return f'[MCP RESPONSE] Handled query: {query}'

@mcp.tool()
def send_msg(text: str = '', audio_base64: str = '', format_ext: str = 'm4a') -> str:
    if text:
        cmd = text
    elif audio_base64:
        url, file_path = _save_temp_audio(audio_base64, format_ext)
        try:
            res = llm.transcribe(url)
            cmd = Utils.extract_llm_response(res)
        finally:
            if os.path.exists(file_path):
                os.remove(file_path)
    else:
        cmd = ''

    Utils.play_text_announcement(cmd)
    return 'OK'

@mcp.tool()
def send_vocal(text: str = '', audio_base64: str = '', format_ext: str = 'm4a') -> str:
    if text:
        cmd = text
    elif audio_base64:
        url, file_path = _save_temp_audio(audio_base64, format_ext)
        try:
            Utils.play_announcement(file_path)
        finally:
            if os.path.exists(file_path):
                os.remove(file_path)
    else:
        cmd = ''
    return 'OK'

@mcp.tool()
def tool_ask_multiroom(text: str = '', audio_base64: str = '', format_ext: str = 'm4a') -> str:
    if text:
        cmd = text
    elif audio_base64:
        url, file_path = _save_temp_audio(audio_base64, format_ext)
        try:
            res = llm.transcribe(url)
            cmd = Utils.extract_llm_response(res)
        finally:
            if os.path.exists(file_path):
                os.remove(file_path)
    else:
        cmd = ''

    Utils.send_command_multiroom(cmd)
    return 'OK'

@mcp.custom_route("/call", methods=["POST"])
async def route_call(request: Request) -> Response:
    try:
        payload = await request.json()
    except Exception:
        return Response('Invalid JSON', status_code=400)

    tool_name = payload.get('tool', '')
    args = payload.get('args', {})
    if not tool_name.startswith('tool_'):
        return Response('Tool not found', status_code=404)

    func = globals().get(tool_name)
    if not callable(func):
        return Response('Tool not found', status_code=404)

    try:
        target_func = func.fn if hasattr(func, "fn") else func
        res = await asyncio.to_thread(target_func, **args)
        return Response(str(res), media_type='text/plain')
    except Exception as e:
        logger.exception("Error executing tool")
        return Response(str(e), status_code=500)

class AuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        if request.url.path.startswith("/audio/"):
            return await call_next(request)

        if request.headers.get('X-Auth-Token') != cfg.conf.token:
            return Response('Unauthorized', status_code=401)
        return await call_next(request)

app = mcp.http_app()
app.add_middleware(AuthMiddleware)

if __name__ == '__main__':
    uvicorn.run(
        app,
        host='0.0.0.0',
        port=cfg.conf.port,
        ssl_certfile=cfg.config_dir.joinpath('cert.pem'),
        ssl_keyfile=cfg.config_dir.joinpath('key.pem'),
    )
