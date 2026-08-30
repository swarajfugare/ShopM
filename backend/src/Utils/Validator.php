<?php
declare(strict_types=1);

namespace Matoshree\Utils;

class Validator {
    public static function getJsonInput(): array {
        $raw = file_get_contents('php://input');
        if (empty($raw)) {
            return [];
        }
        $data = json_decode($raw, true);
        return is_array($data) ? $data : [];
    }

    public static function requireFields(array $input, array $requiredFields): ?string {
        foreach ($requiredFields as $field) {
            if (!isset($input[$field]) || (is_string($input[$field]) && trim($input[$field]) === '')) {
                return "Field '{$field}' is required and cannot be empty.";
            }
        }
        return null;
    }

    public static function sanitizeString(mixed $val): string {
        if ($val === null) return '';
        return trim(htmlspecialchars((string)$val, ENT_QUOTES, 'UTF-8'));
    }

    public static function sanitizeFloat(mixed $val, float $default = 0.0): float {
        if ($val === null || $val === '') return $default;
        return (float)$val;
    }

    public static function sanitizeInt(mixed $val, int $default = 0): int {
        if ($val === null || $val === '') return $default;
        return (int)$val;
    }
}
