# <product name> — AI-Powered Smart Digital Document Repository

Digitize, store and make use of all your organization's documents — text, images, audio and video — on a single, secure platform that runs entirely on-premises.

<Image: product overview screen>


## The challenge

Organizations are dealing with ever-growing volumes of diverse documents: scanned paper records, photos, meeting recordings and video footage. Most of it is scattered across individual computers, hard drives and shared folders.

Finding a single piece of information means opening file after file, replaying recordings and scrubbing through videos.

Sensitive data cannot be sent to Internet-based AI services, while manual data entry costs enormous time and effort.

And it is hard to know who viewed, edited or deleted which document, and when.


## The solution

<product name> is a digital document repository with built-in artificial intelligence that helps organizations:

- Bring all documents into one unified, well-structured repository.
- Automatically turn images, scanned documents, recordings and videos into searchable text.
- Find the right information in seconds and jump straight to the exact page or moment in the original file.
- Share with control — the right people, the right scope.
- Run all AI processing inside the organization's own infrastructure — data never leaves the system.


## Key features

### 1. Automatic AI digitization

Once uploaded, documents are processed by AI automatically in the background — no waiting required:

- Text recognition (OCR): extracts Vietnamese text from images, scanned documents and PDFs, together with the position of the text on each page.
- Speech-to-text: meeting recordings, interviews and videos are transcribed sentence by sentence, each with its own timestamp.
- Image summarization: produces a Vietnamese summary of the scene, objects and activities in an image — so even images without text become searchable by content.
- Video summarization: analyzes both visuals and speech to produce an overall summary plus per-segment summaries; click any segment to play the video from that exact point.

<Image: a digitized document, with recognized text shown side by side with the original page>

### 2. Human review, AI assistance

AI results are always checked by the uploader before entering the official search index:

- View the original page side by side with the recognized text; listen to audio synchronized with each sentence; watch the video next to its summary.
- Edit content and timestamps directly, with a full edit history.
- The system automatically flags low-quality results for the reviewer's attention.
- If nobody acts, results are confirmed automatically after a configurable period (24 hours by default) — except results flagged as low quality.

<Image: speech-to-text review screen>

### 3. Smart search

- Keyword search across all digitized content: text in images and PDFs, speech in recordings and videos, summaries and notes.
- Semantic search: ask questions in natural Vietnamese and the system returns the most relevant passages, with sources cited.
- Precise positioning: click a result to open the exact page of a document or play an audio/video file from the exact second.
- Advanced filters by document type, label, time, folder and group.

<Image: search results with snippets and links to the original position>

### 4. Fast, safe data ingestion

- Upload many files at once, or drag and drop multiple folders in a single go.
- Large-file support with automatic resume after network interruptions.
- Every file is scanned for malware and checked for format and integrity before entering the repository.
- Add notes right at upload time.

### 5. Repository management and classification

- The repository is organized as a folder tree defined by administrators.
- Label documents by topic and document type; filter and analyze by label.
- Add notes to documents, with edit history, fully searchable.
- Safe deletion via a recycle bin: restore to the original location, with automatic permanent deletion after a set retention period.

### 6. Teamwork and controlled sharing

- Create groups, invite members, assign owner and member roles.
- Share documents into groups — one at a time or in bulk — without copying files.
- In-app notifications when new documents are shared.
- Each user only sees documents they uploaded or that were shared into groups they belong to.

### 7. Statistical reports and export

Generate reports from ready-made templates, filtered by reporting period, group, folder, topic and document type:

- Repository status by period: new uploads, deletions and totals; storage size and duration by file type; trend charts over time.
- Digitization progress and quality: results in progress, awaiting review or overdue; share of results that needed corrections or were auto-confirmed.
- Statistics by topic and document type, including unclassified documents.

Export reports to PDF, Excel or Word for printing or sharing. Figures always stay within the access scope of the person creating the report.

<Image: statistical report with charts>

### 8. Personal storage

- Every user has a private storage space, invisible to others, within an assigned quota.
- Move personal files or folders into the shared repository when they need to be shared or put to use.

### 9. Integration with other systems

- Standard APIs let other business applications on the internal network access data.
- Administrators issue and revoke access keys, limit data scope and call rates per key, and monitor usage statistics.


## Security and safety

- Fully on-premises: the system and its AI models run inside the organization's infrastructure and can operate on an isolated network with no Internet connection. Data is never sent outside.
- Need-to-know access: users only see data within their permitted scope — applied consistently across search, reports and APIs.
- Separation of duties: administrators operate the system but cannot read business document content.
- Role-based permissions: administrators turn permissions on or off per role, and the interface adapts automatically.
- Data integrity: every file is stored with a hash; originals cannot be altered after ingestion.
- Full traceability: every action is logged in an audit log that cannot be edited or deleted, with built-in log integrity verification.
- Account protection: automatic lockout after repeated failed logins, warnings and automatic sign-out when a session expires.
- Backup and recovery: regular backups of all data and configuration, with a defined recovery procedure.


## User roles

- Officer: uploads documents, reviews digitization results, classifies, annotates, searches, shares and creates reports.
- Manager: all officer functions, plus monitoring of activity logs within the groups they manage.
- System administrator: manages accounts, permissions, repository structure, system configuration, API keys and logs — with no access to business content.
- External system: accesses data through the API using an issued key.


## Deployment and capacity

- On-premises deployment on the customer's infrastructure, consisting of a GPU-equipped AI processing server, an application and data server, centralized storage and a backup appliance.
- Designed scale: millions of documents, tens of millions of digitized content segments, hundreds of working groups.
- Keyword search responds in about one second; semantic search within a few seconds.
- Background batch digitization at thousands of pages per hour; a single document is typically processed within minutes.
- Processing queues handle large backlogs, retry automatically on errors and lose no work when failures occur.
- Vietnamese user interface, friendly for non-technical users.


## Benefits

- Save time: from hours of manual searching to just seconds.
- Unlock "dormant" data: recordings, videos and photos become searchable sources of information.
- Tight control: know exactly who can see what, and who did what, when.
- Peace of mind: modern AI, while data never leaves the organization.
- Ready to grow: an open architecture that connects with other business systems.


## Ideal for

- Government agencies and organizations that need to digitize and manage large document repositories.
- Units that keep large volumes of minutes, meeting recordings and multimedia materials.
- Organizations with strict security requirements that need to run AI on their internal network.
