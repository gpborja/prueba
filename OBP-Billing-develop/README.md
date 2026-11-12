# OBP-Billing

Billing and monetisation of API usage on the Open Bank Project (OBP) platform.

This repository describes how API usage on OBP is metered, how subscription plans are modelled, and the two ways a bank can charge API consumers for that usage:

1. **Stripe** – implemented in [OBP-Stripe](https://github.com/OpenBankProject/OBP-Stripe) and running on the public OBP sandbox.
2. **The bank's own invoicing / accounting system** – the OBP Billing App design described below.

Both approaches build on the same OBP-API primitives, so the choice of payment path does not change how plans are enforced on the API.

## Building blocks provided by OBP-API

| Concern | OBP-API capability | Endpoints (v5.1.0 unless noted) |
| --- | --- | --- |
| Metering | Every API call is logged with consumer, user, endpoint, verb, response code, duration and timestamp. | `GET /management/metrics`, `GET /management/aggregate-metrics`, `GET /management/metrics/banks/BANK_ID` |
| Plan entitlements | Which endpoints and roles an application or user may use. | `POST /users/USER_ID/entitlements`, `DELETE /users/USER_ID/entitlement/ENTITLEMENT_ID`, `GET /users/USER_ID/entitlements` |
| Plan quotas | Rate limits per consumer: per second, minute, hour, day, week and month. | `GET` and `PUT /management/consumers/CONSUMER_ID/consumer/call-limits` (v3.1.0), `GET /rate-limiting` |
| Suspension | Disable a consumer whose bills are overdue; re-enable on settlement. | `PUT /management/consumers/CONSUMER_ID` |
| Linking to the billing system | Store billing identifiers (customer id, subscription id, plan name) against the OBP user without touching personal data. | `POST /users/USER_ID/non-personal/attributes` |

A subscription plan is therefore a bundle of entitlements, consumer scopes and call limits. Upgrading, downgrading or cancelling a plan means granting or removing entitlements and adjusting call limits. No change to the APIs themselves is required and changes take effect immediately.

Rate limiting is enabled with the `use_consumer_limits` property and requires Redis. Metrics writing is controlled by `write_metrics`.

## Path 1: Stripe (OBP-Stripe)

[OBP-Stripe](https://github.com/OpenBankProject/OBP-Stripe) is a Go application that sits alongside OBP-API and OBP Portal.

Flow:

1. The developer signs in with their OBP account via OAuth2 / OIDC.
2. They choose a plan. Products and prices are defined in the Stripe dashboard, so pricing changes need no deployment.
3. Stripe Checkout takes the payment. The OBP user id is passed as checkout metadata.
4. Stripe calls the OBP-Stripe webhook (`checkout.session.completed`, `customer.subscription.updated`, `customer.subscription.deleted`, `customer.updated`).
5. On successful checkout OBP-Stripe, acting with a service account, grants the plan's entitlements and stores `STRIPE_CUSTOMER_ID`, `STRIPE_SUBSCRIPTION_ID` and `SUBSCRIPTION` as non-personal user attributes.
6. On cancellation or failed renewal it removes the entitlements and attributes.
7. Developers manage cards, invoices and cancellation in the Stripe customer portal.

The service account needs `CanCreateNonPersonalUserAttribute`, `CanCreateEntitlementAtAnyBank` and related roles; OBP-Stripe validates these at startup.

Status: in production on the OBP sandbox at `apisandbox-subscriptions.openbankproject.com`. It currently maps one plan to one entitlement. Mapping plans to call limits and to multiple entitlements is a straightforward extension using the endpoints listed above.

## Path 2: Bank's own invoicing system (OBP Billing App)

For banks that must invoice through their own accounting or core banking system, for example for local currency, tax or regulatory reasons.

API monetisation in this model uses five components:

1. **OBP-API** logs, stores and exposes API usage (metrics) and provides the rate-limiting and consumer APIs.
2. **OBP Billing App** (this repository) reads usage per consumer, creates clients and invoices in the accounting system, and throttles or disables consumers whose invoices are overdue.
3. **REST-based accounting system**, for example Invoice Ninja, which issues the invoices. Any invoicing system with a REST API can be connected.
4. **API consumer** (the third-party application) receives and pays the invoice.
5. **Payment system**, for example Stripe or the bank's own rails, connected to the accounting system, which records the payment.

Periodically the OBP Billing App queries the accounting system for payment status and adjusts consumer call limits or enables / disables consumers accordingly.

![OBP API Monetisation, Billing and Rate Limiting](https://user-images.githubusercontent.com/485218/74542852-ac946c00-4f44-11ea-9ac7-58a2b1453fa4.png)

Status: reference implementation. The Scala application in this repository implements the client and invoice creation step against [Invoice Ninja](https://www.invoiceninja.com/). Overdue handling via the OBP rate-limiting and consumer APIs, and connecting a different invoicing system, are scoped per bank.

### What the OBP Billing App does

On each run the application:

1. Fetches all consumers from OBP-API (`GET /management/consumers`).
2. Fetches the existing clients from Invoice Ninja and creates a client for every consumer that does not yet have one. The OBP consumer id is stored in the client's first custom field.
3. For each consumer, fetches daily aggregate metrics from OBP-API (`GET /management/aggregate-metrics`) for the period since the last invoice.
4. Creates an invoice in Invoice Ninja with one line item per day that had API calls, priced as call count multiplied by the cost of the configured Invoice Ninja product. Days with zero calls produce no line item and periods with zero calls produce no invoice.

### Usage

Requirements: JDK 17 or newer and [sbt](https://www.scala-sbt.org/). The build targets Scala 2.13.

1. Configure the application. Copy `src/main/resources/application.properties.example` to `src/main/resources/application.properties` (git-ignored) and fill in the values, or keep the file anywhere and point at it with the `OBP_BILLING_CONFIG` environment variable or `-Dobp.billing.config=/path/to/file`. Every key can also be set as an environment variable named after the key in upper case with dots replaced by underscores, for example `OBP_PASSWORD` or `NINJA_API_TOKEN`. Environment variables win over the file.

   | Property | Meaning |
   | --- | --- |
   | `obp.api.rootUrl` | Base URL of the OBP-API instance |
   | `obp.api.versionedUrl` | Versioned API URL, for example `${obp.api.rootUrl}/obp/v5.1.0`. Versions v4.0.0 to v6.0.0 are supported. |
   | `obp.api.directloginUrl` | Direct Login endpoint used to obtain a token |
   | `obp.consumerKey`, `obp.username`, `obp.password` | Direct Login credentials of a user with the `CanGetConsumers` and `CanReadAggregateMetrics` roles |
   | `ninja.api.rootUrl` | Invoice Ninja API base URL |
   | `ninja.api.token` | Invoice Ninja API token |
   | `ninja.api.invoice.item.product_key` | Key of the Invoice Ninja product whose cost is the price per API call |

2. Run directly:

   ```
   sbt run
   ```

   Or build a fat jar and run it, for example from a cron job:

   ```
   sbt assembly
   OBP_BILLING_CONFIG=/etc/obp-billing.properties java -jar ./target/scala-2.13/obp-billing.jar
   ```

   The process exits with status 1 if any invoice could not be created.

3. Run the tests with `sbt test`.

### Notes

- The invoicing integration targets the Invoice Ninja **v4** REST API (`X-Ninja-Token` header, `invoice_items`, numeric ids). Invoice Ninja v5 changed the API and is not yet supported.
- Day boundaries for invoice line items are midnight UTC, matching OBP-API metrics timestamps.
- Only lift-json is used at runtime. HTTP goes through the JDK's `java.net.http` client, so no third-party HTTP library is needed.

## Related

- [OBP-API](https://github.com/OpenBankProject/OBP-API)
- [OBP-Stripe](https://github.com/OpenBankProject/OBP-Stripe)
- [OBP-Portal](https://github.com/OpenBankProject/OBP-Portal)
