rootProject.name = "auction"

include(
    "app",
    "bank-api",
    "bank-api:web",
    "features:user-onboarding",
    "features:product-catalog",
    "features:bidding",
    "features:settlement",
)
