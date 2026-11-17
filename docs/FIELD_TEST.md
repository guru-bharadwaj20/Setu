# Setu Field Test Protocol

The simulator shows that the mesh works. Only real phones in real buildings can
show how well it works. This protocol produces numbers that you can compare
between runs and publish.

## What we measure

| Metric | Definition |
|--------|------------|
| Delivery ratio | SOS packets that reached the relay divided by SOS packets sent |
| Delivery time | Relay `receivedAt` minus packet `ts`, as the median and the 90th percentile |
| Hops | The `hops` field when the packet reaches the relay |
| ACK return time | Time from "Mark reached" on the map until the phone shows "rescuers have your location" |
| Battery cost | mAh per hour from Android battery stats, with the screen off and Setu running |

## Equipment

- Phones that all run Setu from the same release, each above 60 % battery.
- One relay you deploy yourself (see `relay/README.md`), with the map open on a laptop.
- One phone with mobile data, called the *gateway*. Put every other phone in airplane mode, then turn Bluetooth and Wi-Fi back on.
- The printed drill sheet (below), a pen and a watch synced to network time.

## Stages

| Stage | Phones | Setting | Pass mark |
|-------|--------|---------|-----------|
| 1. Corridor | 3 | Straight corridor, phones 20 m apart, gateway at one end | The SOS from the far end arrives in under 5 min with `hops` = 2 |
| 2. Building | 5 | Three floors, gateway outside the building | Every SOS arrives in under 15 min |
| 3. Campus | 10 to 15 | 300 m x 300 m, people walking normally, gateway at the gate | 90 % of SOS packets arrive in under 30 min, and 80 % of senders see the ACK within 45 min |

## Steps

1. Open the map with `?relay=<your relay>`. Enter the rescuer token in Settings.
2. Install Setu on every phone, open it, grant the permissions and choose **Courier**.
3. Write each phone's short code on the drill sheet.
4. At T+0, the senders press SOS. Write down the time.
5. Watch the map. When each code appears, write down the time and its hop count.
6. Click **Mark reached** on each SOS. Write down when each sender's screen changes.
7. After 60 minutes, write down each phone's battery percentage and Setu's battery use from Android's battery settings.
8. Press **I'm safe** on every sender. Check that each one turns green on the map.
9. Export the CSV from the map and attach it to the report.

## Drill sheet

```
Run: ____  Date: ____  Stage: ____  Release: ____  Relay: ____

Code     Model          Role     Sent   On map  Hops  Reached  ACK seen  Batt start/end
-------  -------------  -------  -----  ------  ----  -------  --------  --------------
         Gateway
```

## Reporting

Open a *Field test report* issue on GitHub. Attach the drill sheet and the CSV,
and note any phone that stopped relaying once its screen was off. Include the
brand and Android version, because these vary by brand.

## Safety

Drills send real SOS packets. Always run them against your own relay, never the
default one. If the drill takes place in public, tell the local authorities
beforehand.
