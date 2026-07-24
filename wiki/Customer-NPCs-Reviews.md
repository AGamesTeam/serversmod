# Customer NPCs & Reviews

Physical customer NPCs give businesses a visible, living storefront experience: they spawn nearby, walk over, queue, get served (or give up), and leave a randomized written review that feeds back into how often — and how much — future customers show up.

## Spawning

A business only attracts customer NPCs if it's **eligible**: listed, not suspended, not opted out, has at least one queue point *and* one Sell Barrel, is currently open, is discoverable (sign placed, active advert, or a queue point inside a government public square), and has paid every license fee any taxing government requires.

Two independent spawn cycles run continuously:

- **Regular spawns**, every few minutes per business (admin-configurable). Base spawn chance is 85%, scaled by four multipliers:
  - Your **recent review score** — poor reviews can drag this down to 40% of baseline, great reviews boost it up to 160% (see the feedback loop below).
  - **Nearby villagers** — a 30% boost if a real villager is within 48 blocks of your queue point.
  - **Time of day** — 40% less traffic at night.
  - **Weather** — half as much traffic in a thunderstorm, 25% less in rain.
- **Ambient wanderers**, a separate slower cycle (off by default), spawning idle NPCs near online players who roam for up to 10 minutes. A quarter of them start already "aware" of a random eligible business, as if they'd shopped there before; the rest look for a business sign they can actually see and, once spotted, commit to a shopping trip.

New customers spawn at a safe position near whichever of your queue points currently has the fewest people waiting, and never inside another player's land claim.

## Walking, queueing, and being served

There's no reserved "slot number" — a customer's queue position is always computed live by counting how many other active customers are closer to the counter. Customers walk to a point that's exactly one spot back from the front of the line, closing the gap smoothly as it opens up. Once you're first in line and the owner right-clicks you, a purchase offer opens.

You (the owner) get a chat prompt to **Accept**, **Decline**, or occasionally **Haggle** (about 15% of offers arrive with the customer proposing a 10–25% discount — you can accept the lower price, hold firm at full price, or decline outright). Accepting completes the sale: stock leaves your barrels, you're paid (net of sales tax), and a review is generated.

If you've hired an [Employee NPC](Businesses.md#employee-npc), front-of-line customers are auto-served every second without you lifting a finger — online or not.

**Giving up:** a customer waits at most 5 minutes (configurable) at the front of the line before abandoning the visit with a bad review. If your shop closes while someone's still en route or waiting, they leave immediately with a "closed when I got there" review instead of waiting out the timeout.

## Reviews: how the star score works

Every completed visit scores 0–5 stars (rounded to one decimal) starting from a perfect 5.0 and deducting for three things:

- **Price fairness** — how your listed price compares to what other businesses (or the base market price, if you're the only seller) charge for the same items, weighted by how much of the basket that item represents. Up to a 2.0-star deduction for badly overpriced goods.
- **Wait time** — how long they stood at the front of the line before being served. Up to a 1.3-star deduction for a very long wait.
- **Danger** — whether they took damage or ran into hostile mobs on the way, or crossed through a trade-war zone. Up to a 1.5-star deduction for a genuinely dangerous trip.

Declined, timed-out, and closed-shop visits use simpler, mostly-random star bands instead of the full formula (declined visits, for instance, are attributed to a reviewer literally named "Karen").

**Why no two reviews read the same:** the written text is assembled from independent phrase pools — an opener, a price comment, a wait comment, a danger-or-safety comment, and (if the customer has a home city/country) a local-vs-foreign comment — each picked randomly from 4–10 variants, shuffled into random order, sometimes with a closing sentence. Each customer also has a randomly-rolled **trait** (Frugal, Big Spender, Impatient, Picky, Generous, VIP, Influencer, or no trait) that shifts how much they spend, how patient they are, how price-sensitive they are, and how lenient their review runs — VIP and Influencer customers are rare, high-value visits, and a good review (≥3.5★) from an Influencer automatically buys the business a free advertising boost.

## Loyalty and regulars

Businesses remember up to 20 recent customer names (by their base name, ignoring the trait prefix that changes every visit). The more times a tracked name has visited, the more lenient their reviews get, up to a cap. Roughly 30% of new spawns reuse a familiar regular's name instead of generating a fresh one, so "regulars" visibly return over time. The **Loyalty Card** upgrade adds a separate bonus: every 5th completed sale gets extra review leniency regardless of who the customer is.

## Reviews → traffic feedback loop

A business's average star rating over the last few days (default 72 hours; falls back to a neutral 3.0 with no recent reviews so quiet shops aren't punished) is converted into a single multiplier:

- 0★ average → as low as **0.4×**
- 3★ average → exactly **1.0×** (neutral)
- 5★ average → as high as **1.6×**

That same multiplier controls **both** how often new customers spawn *and* how much money each customer's basket can spend — so a business trending toward great reviews gets more frequent, bigger-spending customers, and one trending toward bad reviews gets rarer, cheaper visits. It's the core reputation mechanic tying review quality directly to revenue.

## Owner replies and moderation

Business owners can post one public reply per review (overwritable, but only ever one at a time), shown right under it on the storefront page. Only admins can delete a review outright — there's no editing. Reviews from real players buying through the web panel (one per completed order, star rating plus a checklist of tags, no freeform text) show up in the same list as NPC-generated reviews, distinguished by a source badge.

## Related mechanics

- **Diplomatic discount:** customers from a government your business's taxing government has allied with pay 10% less.
- **Farmer bias:** with a villager farmer nearby, baskets lean toward farm goods (seeds, crops, bread).
- **Theft:** a separate, optional risk — see [Businesses](Businesses.md#theft).

See [Configuration](Configuration.md) for every tunable value (timeout length, queue capacity, review window, min/max rate multipliers), most of which live in the admin web panel's business settings rather than the Forge config file.
