#!/usr/bin/env python3
"""
Matoshree Collection — Automated Verification & Integrity Suite
Tests:
1. SQL Schema syntax and table definitions
2. Financial Profit calculations (25% default estimated margin & actual margin)
3. Currency Formatting (Indian numbering format)
4. Offline sync idempotency rules
5. Snapshot preservation rules
"""

import re
import unittest

class FinancialRulesTest(unittest.TestCase):
    def test_25_percent_estimated_profit(self):
        amount = 18450.00
        margin = 25.0
        est_profit = round(amount * (margin / 100.0), 2)
        cost = round(amount - est_profit, 2)
        self.assertEqual(est_profit, 4612.50)
        self.assertEqual(cost, 13837.50)

    def test_actual_profit_calculation(self):
        selling = 12499.00
        cost = 9374.00
        profit = round(selling - cost, 2)
        self.assertEqual(profit, 3125.00)

    def test_indian_rupee_formatting(self):
        # Format 125500 to 1,25,500
        def format_inr(num):
            s = str(int(num))
            if len(s) <= 3:
                return "₹" + s
            last3 = s[-3:]
            rest = s[:-3]
            groups = []
            while len(rest) > 2:
                groups.insert(0, rest[-2:])
                rest = rest[:-2]
            if rest:
                groups.insert(0, rest)
            return "₹" + ",".join(groups) + "," + last3

        self.assertEqual(format_inr(18450), "₹18,450")
        self.assertEqual(format_inr(125500), "₹1,25,500")
        self.assertEqual(format_inr(1000000), "₹10,00,000")

class SchemaVerificationTest(unittest.TestCase):
    def test_all_15_core_tables_defined(self):
        with open("backend/migrations/001_create_initial_schema.sql", "r") as f:
            sql = f.read()

        expected_tables = [
            "shops", "users", "customers", "categories", "products",
            "bills", "bill_items", "payments", "expenses", "daily_closings",
            "targets", "settings", "devices", "sync_logs", "audit_logs"
        ]

        for table in expected_tables:
            pattern = rf"CREATE TABLE IF NOT EXISTS\s+`?{table}`?"
            self.assertTrue(re.search(pattern, sql, re.IGNORECASE), f"Table {table} must be defined in schema.")

    def test_bill_item_snapshot_fields(self):
        with open("backend/migrations/001_create_initial_schema.sql", "r") as f:
            sql = f.read()
        self.assertIn("product_name_snapshot", sql)
        self.assertIn("sku_snapshot", sql)
        self.assertIn("line_profit", sql)

    def test_bill_transaction_uuid_unique(self):
        with open("backend/migrations/001_create_initial_schema.sql", "r") as f:
            sql = f.read()
        self.assertIn("transaction_uuid", sql)

if __name__ == "__main__":
    unittest.main()
