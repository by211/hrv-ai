# Google Play publication checklist

## Permanent identity

- Application ID: `quest.byai.hrv`
- Play Console app: Created as `HRV AI`.
- Website: Leave blank; HRV AI is an Android-only product.
- Privacy policy: `https://by211.github.io/hrv-ai/privacy-policy.html` (published and verified).
- Current internal-test version: `0.1.1` (`versionCode` 2)

Treat the application ID and public app name as permanent now that the Play Console app exists.

## Build and signing

- Create a dedicated upload key named `hrv-upload`.
- Keep the keystore outside the repository and maintain an encrypted backup.
- Copy `keystore.properties.example` to the ignored `keystore.properties` file and provide its real values.
- Enroll the new Play app in Play App Signing.
- Upload the signed `app-release.aab`, not the unsigned release APK.
- Increment `versionCode` for every subsequent Play upload.

## Store listing

- Use `listing/en-US.md` as the source for the English listing.
- Upload `graphics/play-store-icon.png` as the 512 × 512 store icon.
- Upload `graphics/feature-graphic.png` as the 1024 × 500 feature graphic.
- Upload at least two accurate phone screenshots from `screenshots/phone/`.
- Support email: `brian.y@koowote.com`.
- Do not provide a product website; the standalone public privacy-policy URL is sufficient.

## App content declarations

- Ads: No.
- App access: All functionality is available without an account, but a compatible Polar sensor is required for live sessions. Supply reviewer instructions explaining the sensor dependency and diagnostics screen.
- Target audience: Adults; the app is not directed to children.
- Content rating: Complete the questionnaire using the app's wellness content.
- Health Apps declaration:
  - Activity and Fitness — the app processes heart rate and R-R intervals.
  - Stress Management, Relaxation, Mental Acuity — the app provides paced-breathing guidance.
  - Do not declare Medical Device Apps; the product is explicitly wellness/biofeedback software.
- Privacy policy: Published from `docs/privacy-policy.html` through GitHub Pages; the declared HTTPS URL returns the current policy.

## Proposed Data safety answers

Verify these against the final release bundle and Play Console wording before submission:

- Data shared with third parties: No.
- Data collected by the developer (transmitted off device): No.
- Account creation: None.
- Ads or analytics SDKs: None.
- Health and fitness data is processed and stored only on-device.
- User-directed file export is initiated and controlled by the user.
- Users can delete individual sessions or all application data in the app.

## Test and rollout

1. Upload to Internal testing.
2. Install through Google Play on a physical Android phone.
3. Validate discovery, connection, R-R confirmation, a fixed session, an adaptive session, export, backgrounding, reconnect, and deletion with a Polar H9.
4. Review Android vitals and tester feedback.
5. Move to Closed testing.
6. If Play Console requires it, keep at least 12 testers opted in continuously for 14 days before applying for production access.
7. Submit a staged production rollout only after the physical H9 acceptance tests pass.

## Reviewer notes draft

HRV AI does not require an account. Its core live-session functionality requires a compatible Polar Bluetooth Low Energy chest strap that exposes heart rate and R-R intervals; the Polar H9 is the primary supported sensor. To connect, wet the strap electrodes, wear the strap, close other applications connected to the sensor, open **Polar sensor**, grant Nearby Devices access, scan, and select the matching device ID. The diagnostics card distinguishes a Bluetooth/heart-rate connection from confirmed R-R streaming. The app stores measurements locally and provides in-app export and deletion controls.
