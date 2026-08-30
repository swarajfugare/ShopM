<?php
declare(strict_types=1);

namespace Matoshree\Utils;

class Response {
    public static function json(
        mixed $data = null,
        string $message = 'Success',
        int $statusCode = 200,
        bool $success = true
    ): void {
        http_response_code($statusCode);
        header('Content-Type: application/json; charset=utf-8');

        $response = [
            'status'    => $success ? 'success' : 'error',
            'message'   => $message,
            'data'      => $data,
            'timestamp' => time()
        ];

        echo json_encode($response, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        exit;
    }

    public static function success(mixed $data = null, string $message = 'Operation successful', int $statusCode = 200): void {
        self::json($data, $message, $statusCode, true);
    }

    public static function error(string $message = 'An error occurred', int $statusCode = 400, mixed $data = null): void {
        self::json($data, $message, $statusCode, false);
    }

    public static function notFound(string $message = 'Resource not found'): void {
        self::error($message, 404);
    }

    public static function unauthorized(string $message = 'Unauthorized'): void {
        self::error($message, 401);
    }

    public static function forbidden(string $message = 'Forbidden'): void {
        self::error($message, 403);
    }
}
