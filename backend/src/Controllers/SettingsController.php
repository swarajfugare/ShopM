<?php
declare(strict_types=1);

namespace Matoshree\Controllers;

use Matoshree\Config\Database;
use Matoshree\Utils\Response;
use Matoshree\Utils\Validator;
use PDO;

class SettingsController {
    public static function index(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $pdo = Database::getConnection();

        // 1. Fetch shop profile
        $shopStmt = $pdo->prepare("SELECT * FROM shops WHERE id = ? LIMIT 1");
        $shopStmt->execute([$shopId]);
        $shop = $shopStmt->fetch(PDO::FETCH_ASSOC);

        // 2. Fetch custom settings
        $setStmt = $pdo->prepare("SELECT setting_key, setting_value FROM settings WHERE shop_id = ?");
        $setStmt->execute([$shopId]);
        $rows = $setStmt->fetchAll(PDO::FETCH_ASSOC);

        $settings = [
            'default_profit_margin' => 25.0,
            'default_payment_method' => 'CASH',
            'bill_prefix'           => 'MC',
            'enable_biometric'      => 'true',
            'auto_lock_minutes'     => '5',
            'language'              => 'en',
            'theme'                 => 'emerald_gold'
        ];

        foreach ($rows as $row) {
            $settings[$row['setting_key']] = $row['setting_value'];
        }

        Response::success([
            'shop'     => $shop,
            'settings' => $settings
        ], 'Settings retrieved');
    }

    public static function update(array $authUser): void {
        $shopId = (int)$authUser['shop_id'];
        $input = Validator::getJsonInput();
        $pdo = Database::getConnection();

        // 1. Update Shop basic info if provided
        if (isset($input['shop'])) {
            $s = $input['shop'];
            $name = Validator::sanitizeString($s['name'] ?? '');
            $address = Validator::sanitizeString($s['address'] ?? '');
            $city = Validator::sanitizeString($s['city'] ?? '');
            $state = Validator::sanitizeString($s['state'] ?? '');
            $pincode = Validator::sanitizeString($s['pincode'] ?? '');
            $mobile = Validator::sanitizeString($s['mobile'] ?? '');
            $email = Validator::sanitizeString($s['email'] ?? '');
            $gst = Validator::sanitizeString($s['gst_number'] ?? '');

            if (!empty($name)) {
                $upShop = $pdo->prepare("
                    UPDATE shops 
                    SET name = ?, address = ?, city = ?, state = ?, pincode = ?, mobile = ?, email = ?, gst_number = ?
                    WHERE id = ?
                ");
                $upShop->execute([$name, $address, $city, $state, $pincode, $mobile, $email, $gst, $shopId]);
            }
        }

        // 2. Update custom settings keys
        if (isset($input['settings']) && is_array($input['settings'])) {
            $setStmt = $pdo->prepare("
                INSERT INTO settings (shop_id, setting_key, setting_value)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value)
            ");
            foreach ($input['settings'] as $key => $val) {
                $setStmt->execute([$shopId, (string)$key, (string)$val]);
            }
        }

        self::index($authUser);
    }
}
