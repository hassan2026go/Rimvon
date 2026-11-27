# دليل استضافة Rimvon وتثبيته

لا يحتاج Rimvon إلى GitHub ولا Firebase ولا خدمات جوجل. تحتاج فقط إلى:

1. **سيرفر VPS** صغير (1 CPU و1 GB رام يكفيان لمئات المستخدمين) بنظام Ubuntu 22.04 أو 24.04. اختر مزوّداً غير محجوب في بلدك. (Oracle Cloud يقدّم طبقة مجانية، وغيره بأسعار رمزية؛ تأكد من الشروط الحالية عند المزوّد.)
2. **دومين** (اسم موقع) يشير إلى عنوان السيرفر. يلزم لـ HTTPS، وهو ضروري لحماية كلمة السر أثناء الدخول.
3. **Android Studio** على حاسوبك لبناء ملف الـ APK مرة واحدة.

> **تريد كل شيء مجاناً؟** اقرأ آخر الملف: «ملحق: استضافة مجانية بالكامل» (سيرفر Oracle المجاني + نطاق DuckDNS).

> لا تستخدم استضافات مجانية «تنام» أو تمسح القرص (مثل الخطط المجانية لبعض المنصات)، لأن الخادم يحفظ الحسابات في ملف على القرص، وضياعه يعني ضياع كل الأرقام.

---

## 1) تجهيز السيرفر

نفّذ بصلاحيات sudo. استبدل `chat.example.com` بدومينك.

```bash
# أ) أنشئ سجل A في إعدادات الدومين: chat.example.com  ->  عنوان IP للسيرفر

# ب) البرامج
sudo apt update && sudo apt install -y curl ufw gnupg debian-keyring debian-archive-keyring apt-transport-https
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt install -y nodejs

# Caddy (يجلب شهادة HTTPS ويجددها تلقائياً)
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy

# ج) الجدار الناري
sudo ufw allow OpenSSH
sudo ufw allow 80,443/tcp
sudo ufw enable
```

## 2) رفع الخادم وتشغيله

من حاسوبك (داخل مجلد المشروع):

```bash
scp -r server deploy user@SERVER_IP:/tmp/
```

ثم على السيرفر:

```bash
sudo useradd --system --home /opt/rimvon rimvon
sudo mkdir -p /opt/rimvon /var/lib/rimvon
sudo cp -r /tmp/server /opt/rimvon/server
cd /opt/rimvon/server && sudo npm install --omit=dev
sudo chown -R rimvon:rimvon /var/lib/rimvon

sudo cp /tmp/deploy/rimvon.env.example /etc/rimvon.env && sudo chmod 600 /etc/rimvon.env
sudo cp /tmp/deploy/rimvon.service /etc/systemd/system/rimvon.service
sudo systemctl daemon-reload && sudo systemctl enable --now rimvon

# عدّل الدومين داخل الملف أولاً: nano /tmp/deploy/Caddyfile
sudo cp /tmp/deploy/Caddyfile /etc/caddy/Caddyfile && sudo systemctl reload caddy
```

اختبر: افتح `https://chat.example.com/health` في المتصفح، يجب أن يظهر `{"ok":true}`، وعلى الصفحة الرئيسية ترى صفحة Rimvon.

**بديل Docker:** من مجلد `server`: `docker build -t rimvon .` ثم `docker run -d --restart=always -p 127.0.0.1:8080:8080 -v rimvon-data:/data rimvon` (وتبقى إعدادات Caddy كما هي).

## 3) خادم المكالمات TURN (موصى به)

بدونه قد تفشل المكالمات بين أشخاص خلف شبكات جوال أو راوترات صارمة.

```bash
sudo apt install -y coturn
openssl rand -hex 32            # انسخ الناتج: هذا هو السر
sudo cp /tmp/deploy/turnserver.conf /etc/turnserver.conf
sudo nano /etc/turnserver.conf  # ضع السر في static-auth-secret وعدّل realm
echo 'TURNSERVER_ENABLED=1' | sudo tee /etc/default/coturn
sudo systemctl enable --now coturn
sudo ufw allow 3478 && sudo ufw allow 49152:65535/udp
```

ثم أنشئ سجل A آخر `turn.example.com` لنفس السيرفر، وعدّل `/etc/rimvon.env` (ضع السر نفسه في `TURN_SECRET` وعدّل `TURN_URLS` و`STUN_URLS`) وأعد التشغيل: `sudo systemctl restart rimvon`.

## 4) بناء التطبيق (APK)

1. ثبّت Android Studio (إصدار حديث) وافتح مجلد **`android`**. دعه يحمّل ملفات Gradle (يحتاج إنترنت مرة واحدة).
2. أنشئ مفتاح التوقيع **مرة واحدة** واحتفظ به ونسخته الاحتياطية (بدونه لا يمكنك تحديث التطبيق لاحقاً):
   ```bash
   cd android
   keytool -genkeypair -v -keystore rimvon-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias rimvon
   cp keystore.properties.example keystore.properties   # ثم ضع كلمات السر فيه
   ```
3. من القائمة: **Build ▸ Build APK(s)** (اختر نوع release)، أو من الطرفية `./gradlew assembleRelease`.
4. الناتج: `android/app/build/outputs/apk/release/app-release.apk`.

> **مهم:** كُتب هذا الكود دون أن يُبنى على جهاز حقيقي (لم تتوفر بيئة أندرويد عند كتابته). الخادم مُختبر آلياً ونجح، أما التطبيق فمُراجع يدوياً. إن ظهر خطأ عند أول بناء فانسخ نصه كما هو وأرسله لي لأصلحه.

## 5) نشر التطبيق على موقعك (بدون Google Play)

```bash
scp app-release.apk user@SERVER_IP:/tmp/
sudo cp /tmp/app-release.apk /opt/rimvon/server/public/rimvon.apk
```

يصبح رابط التحميل `https://chat.example.com/rimvon.apk`، وفي الصفحة الرئيسية زر تحميل جاهز. شارك الرابط مع أصدقائك.

## 6) الاستخدام لأول مرة

1. على الهاتف: افتح الرابط ونزّل الملف، واسمح بـ «التثبيت من مصادر غير معروفة» عند طلبه.
2. افتح التطبيق واكتب عنوان الخادم `chat.example.com` ثم **إنشاء رقم جديد**.
3. اضبط كلمة سر (أو تخطّ، لكن بدونها لا تستطيع الدخول لحسابك بعد تسجيل الخروج).
4. من **الإعدادات ▸ استمرار وصول الرسائل** اسمح للتطبيق بالعمل في الخلفية. في بعض الهواتف (شاومي، هواوي، سامسونج…) فعّل أيضاً «التشغيل التلقائي» وقفل التطبيق في قائمة التطبيقات الأخيرة، وإلا قد يقتله النظام ويتأخر وصول الرسائل.
5. أضف صديقاً من زر **+** برقمه، وتأكد من رمز الأمان من صفحة معلوماته.

## 7) إذا حُجب عنوانك

- اشترِ دومين ثانياً يشير إلى السيرفر نفسه، وأضفه في **الإعدادات ▸ الخادم** كسطر جديد. التطبيق يبدّل بين العناوين تلقائياً.
- أضف الدومين الجديد أيضاً في `Caddyfile` (`chat.example.com, chat2.example.org { ... }`).
- كحل أخير يمكن استخدام `ws://IP:المنفذ` (بدون تشفير النقل، فكلمة السر تنتقل مكشوفة لهذا الاتصال — استخدمه مؤقتاً فقط).

## 8) الصيانة

| المهمة | الأمر |
|---|---|
| حالة الخادم | `sudo systemctl status rimvon` |
| السجلات | `sudo journalctl -u rimvon -f` |
| نسخ احتياطي | انسخ `/var/lib/rimvon/accounts.json` دورياً (ضياعه = ضياع كل الأرقام) |
| تحديث الخادم | استبدل `server.js` و`public/` ثم `sudo systemctl restart rimvon` |
| تحديث التطبيق | ابنِ APK جديداً بنفس المفتاح وبـ `versionCode` أعلى في `app/build.gradle.kts` وانسخه إلى `public/rimvon.apk` |

## ما يحميه Rimvon وما لا يحميه

- **محمي:** محتوى الرسائل والملفات والمكالمات مشفّر من طرف إلى طرف؛ الخادم لا يستطيع قراءته. كلمات السر مخزّنة بصيغة scrypt. لا قاعدة بيانات محادثات على الخادم.
- **غير محمي:** صاحب السيرفر يرى من يراسل من ومتى وحجم الرسائل وعناوين IP (البيانات الوصفية). والمفاتيح ثابتة لكل حساب (بلا «سرية أمامية» مثل Signal)، فلو سُرّب مفتاح جهاز انكشفت محادثاته المسجّلة.
- **حدود:** جهاز واحد لكل حساب، ولا تنتقل المحادثات إلى جهاز جديد. الملفات حتى 3 ميغابايت. الرسائل لغير المتصل تُحفظ مشفّرة 7 أيام ثم تُحذف. لا توجد مجموعات في هذا الإصدار.

---

## ملحق: استضافة مجانية بالكامل (Oracle Cloud + DuckDNS)

نفس الخطوات أعلاه، مع هذه الفروقات. (الشروط والحصص تتغير؛ راجع صفحات المزوّدين قبل البدء.)

### أ) إنشاء السيرفر المجاني
1. سجّل في Oracle Cloud Free Tier (`cloud.oracle.com/free`). اختر **المنطقة (Region)** بعناية لأنها لا تتغير بعد التسجيل. قد يطلب التسجيل بطاقة للتحقق.
2. **Compute ▸ Instances ▸ Create instance**:
   - Image: **Ubuntu 22.04 أو 24.04** (نسخة aarch64/ARM).
   - Shape: **Ampere ▸ VM.Standard.A1.Flex** بنواة أو نواتين و6–12 GB رام (ضمن الحصة المجانية الحالية).
   - ألصق **مفتاح SSH العام** الخاص بك، وتأكد أن الجهاز يحصل على عنوان IP عام.
   - إن ظهر «Out of capacity» فجرّب لاحقاً أو غيّر Availability Domain.
3. **افتح المنافذ في جدار أوراكل:** Networking ▸ Virtual Cloud Networks ▸ شبكتك ▸ Subnet ▸ Security List ▸ **Add Ingress Rules** (المصدر `0.0.0.0/0`):
   - TCP: `80` و`443` و`3478`
   - UDP: `3478` و`49152-65535`
4. **افتح المنافذ داخل السيرفر.** صور Ubuntu في أوراكل تأتي بقواعد iptables تحجب الدخول. نفّذ بدل خطوة `ufw` في القسم 1:
   ```bash
   sudo iptables -I INPUT 1 -p tcp -m multiport --dports 80,443,3478 -j ACCEPT
   sudo iptables -I INPUT 1 -p udp -m multiport --dports 3478,49152:65535 -j ACCEPT
   sudo apt install -y iptables-persistent     # اختر Yes لحفظ القواعد
   sudo netfilter-persistent save
   ```
   لا تفعّل `ufw` مع هذه الطريقة حتى لا تتعارض القواعد.
5. تثبيت Node يعمل على ARM بنفس أوامر القسم 1، وكذلك Caddy وcoturn.

> احتفظ دائماً بنسخة احتياطية من `/var/lib/rimvon/accounts.json` على حاسوبك، فالطبقات المجانية قد تتغير أو تُوقف أجهزة دون إنذار. وراجع في وثائق أوراكل سياسة استرداد الأجهزة الخاملة.

### ب) نطاق مجاني (DuckDNS)
1. ادخل `duckdns.org` وسجّل الدخول بأحد الحسابات المتاحة، وأنشئ نطاقاً فرعياً مثل `myrimvon.duckdns.org`.
2. ضع في خانة IP عنوان السيرفر العام. هذا يغني عن شراء دومين.
3. استخدم هذا النطاق نفسه في كل مكان: `Caddyfile` (يجلب شهادة HTTPS مجانية من Let's Encrypt)، وفي التطبيق، وفي إعدادات TURN:
   - `turnserver.conf`: `realm=myrimvon.duckdns.org`
   - `/etc/rimvon.env`: `TURN_URLS=turn:myrimvon.duckdns.org:3478?transport=udp,turn:myrimvon.duckdns.org:3478?transport=tcp` و`STUN_URLS=stun:myrimvon.duckdns.org:3478` (لا حاجة لنطاق ثانٍ).
4. **إن كان DuckDNS محجوباً في بلدك** (أو الدخول إليه): جرّب مزوّد نطاقات فرعية مجانية آخر، أو أضف عدة نطاقات تشير لنفس السيرفر وسجّلها كعناوين بديلة في **الإعدادات ▸ الخادم** (القسم 7).

### ج) خيار جهاز منزلي بدل Oracle
يصلح كمبيوتر قديم أو Raspberry Pi يعمل دائماً. ستحتاج إلى فتح المنافذ 80/443 في الراوتر وتوجيهها للجهاز، وهذا لا ينجح مع مزوّدي إنترنت يستخدمون CGNAT (لا يعطونك عنواناً عاماً). بديل بلا فتح منافذ: Cloudflare Tunnel، لكنه يتطلب دومين تملكه على Cloudflare؛ أما «Quick Tunnel» المجاني بلا دومين فمخصص للتجارب وعنوانه عشوائي ولا يصلح لتطبيق يعتمد عليه الناس. وانقطاع الكهرباء أو الإنترنت عندك يعني توقف الخدمة عن الجميع.
