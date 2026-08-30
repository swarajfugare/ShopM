<?php
declare(strict_types=1);

namespace Matoshree\Utils;

use Exception;

class JWT {
    private static function base64UrlEncode(string $data): string {
        return str_replace(['+', '/', '='], ['-', '_', ''], base64_encode($data));
    }

    private static function base64UrlDecode(string $data): string {
        $remainder = strlen($data) % 4;
        if ($remainder) {
            $data .= str_repeat('=', 4 - $remainder);
        }
        return base64_decode(str_replace(['-', '_'], ['+', '/'], $data));
    }

    public static function encode(array $payload, string $secret, int $expiryDays = 90): string {
        $header = ['alg' => 'HS256', 'typ' => 'JWT'];
        
        $now = time();
        $payload['iat'] = $now;
        $payload['exp'] = $now + ($expiryDays * 86400);

        $headerEncoded  = self::base64UrlEncode(json_encode($header, JSON_UNESCAPED_SLASHES));
        $payloadEncoded = self::base64UrlEncode(json_encode($payload, JSON_UNESCAPED_SLASHES));

        $signature = hash_hmac('sha256', "{$headerEncoded}.{$payloadEncoded}", $secret, true);
        $signatureEncoded = self::base64UrlEncode($signature);

        return "{$headerEncoded}.{$payloadEncoded}.{$signatureEncoded}";
    }

    public static function decode(string $token, string $secret): ?array {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return null;
        }

        [$headerEncoded, $payloadEncoded, $signatureEncoded] = $parts;

        $expectedSig = hash_hmac('sha256', "{$headerEncoded}.{$payloadEncoded}", $secret, true);
        if (!hash_equals($expectedSig, self::base64UrlDecode($signatureEncoded))) {
            return null; // Signature verification failed
        }

        $payload = json_decode(self::base64UrlDecode($payloadEncoded), true);
        if (!$payload || !is_array($payload)) {
            return null;
        }

        if (isset($payload['exp']) && $payload['exp'] < time()) {
            return null; // Token expired
        }

        return $payload;
    }
}
