# Leaflet 1.9.4

Stable release pinned and served locally. Source: official npm package `leaflet@1.9.4`; release information: https://leafletjs.com/download.html. License is included in this directory. Library distribution files are unchanged.

Only visible campus-region tiles are requested from OpenStreetMap. No geolocation, prefetch, bulk download or offline-map cache is implemented. Image referrer policy sends the site origin rather than account/route identifiers. Browser caching follows the provider's response headers. The app always displays OpenStreetMap attribution and an external map link.

Tile policy: https://operations.osmfoundation.org/policies/tiles/. Automated tests abort external tile requests and verify product states without downloading maps.
