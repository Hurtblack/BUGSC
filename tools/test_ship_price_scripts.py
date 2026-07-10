import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))

import export_rsi_ship_prices
import export_uex_shipfit


class RsiShipPriceExportTest(unittest.TestCase):
    def test_parse_upgrade_response_exports_msrp_and_sale_price(self):
        doc = {
            "data": {
                "ships": [
                    {
                        "id": 102,
                        "name": "Avenger Titan",
                        "msrp": 6000,
                        "skus": [
                            {"id": 1, "title": "Warbond Edition", "available": True, "price": 5500},
                            {"id": 2, "title": "Standard Edition", "available": True, "price": 6000},
                        ],
                    },
                    {
                        "id": 28,
                        "name": "Idris-P",
                        "msrp": 190000,
                        "skus": [],
                    },
                    {
                        "id": 62,
                        "name": "Carrack",
                        "msrp": 60000,
                        "skus": [
                            {"id": 3, "title": "Anvil Carrack Upgrade", "available": True, "price": 50000},
                            {"id": 4, "title": "Warbond Edition", "available": True, "price": 57000},
                        ],
                    },
                ]
            }
        }

        ships = export_rsi_ship_prices.parse_upgrade_response(doc)

        self.assertEqual(3, len(ships))
        self.assertEqual(6000, ships[0]["msrp_cents"])
        self.assertEqual(6000, ships[0]["sale_price_cents"])
        self.assertEqual(190000, ships[1]["sale_price_cents"])
        self.assertEqual(60000, ships[2]["sale_price_cents"])

    def test_apply_sale_prices_adds_sale_price_to_uex_ships(self):
        ships = [
            {"id": 27, "name": "Avenger Titan", "name_full": "Aegis Avenger Titan"},
            {"id": 79, "name": "F8C Lightning", "name_full": "Anvil F8C Lightning"},
            {"id": 166, "name": "C2 Hercules Starlifter", "name_full": "Crusader C2 Hercules Starlifter"},
            {"id": 154, "name": "Nova Tank", "name_full": "Tumbril Nova Tank"},
        ]
        prices = [
            {"id": 102, "name": "Avenger Titan", "sale_price_cents": 6000},
            {"id": 167, "name": "C2 Hercules", "sale_price_cents": 40000},
            {"id": 154, "name": "Nova", "sale_price_cents": 12000},
        ]

        matched = export_uex_shipfit.apply_sale_prices(ships, prices)

        self.assertEqual(3, matched)
        self.assertEqual(6000, ships[0]["sale_price_cents"])
        self.assertNotIn("sale_price_cents", ships[1])
        self.assertEqual(40000, ships[2]["sale_price_cents"])
        self.assertEqual(12000, ships[3]["sale_price_cents"])


if __name__ == "__main__":
    unittest.main()
