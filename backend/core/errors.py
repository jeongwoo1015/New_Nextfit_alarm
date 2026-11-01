"""API 오류 형식. 모든 오류는 {"error": {"code", "message"}} 로 응답한다."""
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse


class AppError(Exception):
    status_code = 400
    code = "BAD_REQUEST"

    def __init__(self, message: str, code: str | None = None):
        super().__init__(message)
        self.message = message
        if code:
            self.code = code


class Unauthorized(AppError):
    status_code = 401
    code = "UNAUTHORIZED"


class Forbidden(AppError):
    """요청한 데이터의 USER_ID가 로그인 사용자와 다를 때 (service_role이라 RLS가 막아 주지 않는다)."""
    status_code = 403
    code = "FORBIDDEN"


class NotFound(AppError):
    status_code = 404
    code = "NOT_FOUND"


class Conflict(AppError):
    status_code = 409
    code = "CONFLICT"


class ValidationFailed(AppError):
    status_code = 422
    code = "VALIDATION_FAILED"


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(AppError)
    async def _app_error(_: Request, exc: AppError):
        return JSONResponse(status_code=exc.status_code, content={"error": {"code": exc.code, "message": exc.message}})

    @app.exception_handler(RequestValidationError)
    async def _request_error(_: Request, exc: RequestValidationError):
        """요청 형식 오류도 같은 모양으로. details에 어느 필드가 왜 틀렸는지 담는다."""
        details = [{"field": ".".join(str(p) for p in e["loc"] if p != "body"), "message": e["msg"]} for e in exc.errors()]
        return JSONResponse(status_code=422, content={"error": {"code": "VALIDATION_FAILED", "message": "요청 형식이 올바르지 않아요",
                                                                "details": details}})
