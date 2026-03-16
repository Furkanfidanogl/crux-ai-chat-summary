package com.furkanfidanoglu.cruxaisummarize.util.managers;

import com.furkanfidanoglu.cruxaisummarize.data.model.FirebaseDB;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class FirebaseDBManager {

    private static final String COLLECTION_USERS = "users";
    private final FirebaseFirestore db;
    private final FirebaseStorage storage;
    private static FirebaseDBManager instance;

    public static synchronized FirebaseDBManager getInstance() {
        if (instance == null) {
            instance = new FirebaseDBManager();
        }
        return instance;
    }

    private FirebaseDBManager() {
        this.db = FirebaseFirestore.getInstance();
        this.storage = FirebaseStorage.getInstance();
    }

    // --- 1. RESİM YÜKLEME ---
    public void uploadImageToStorage(byte[] fileBytes, StorageCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onError("User not authenticated.");
            return;
        }
        if (fileBytes == null) {
            callback.onError("No file data found.");
            return;
        }

        String fileName = UUID.randomUUID().toString() + ".jpg";
        String path = "uploads/" + user.getUid() + "/" + fileName;

        StorageReference ref = storage.getReference().child(path);

        ref.putBytes(fileBytes)
                .addOnSuccessListener(taskSnapshot -> {
                    ref.getDownloadUrl().addOnSuccessListener(uri -> {
                        callback.onSuccess(uri.toString());
                    });
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // --- 1.1 GENEL DOSYA YÜKLEME ---
    public void uploadByteFileToStorage(byte[] fileBytes, String extension, StorageCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || fileBytes == null) {
            callback.onError("User or file error.");
            return;
        }

        String fileName = UUID.randomUUID().toString();
        if (extension != null && !extension.isEmpty()) {
            fileName += "." + extension;
        }

        String path = "uploads/" + user.getUid() + "/" + fileName;
        StorageReference ref = storage.getReference().child(path);

        ref.putBytes(fileBytes)
                .addOnSuccessListener(task -> ref.getDownloadUrl().addOnSuccessListener(uri -> callback.onSuccess(uri.toString())))
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // --- 2. KULLANICI OLUŞTURMA ---
    public void createUserIfNotExist() {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) return;

        DocumentReference docRef = db.collection(COLLECTION_USERS).document(firebaseUser.getUid());

        docRef.get().addOnSuccessListener(documentSnapshot -> {
            if (!documentSnapshot.exists()) {
                // Kullanıcı yoksa oluşturuluyor (Burada DELETE yok, sadece SET var)
                FirebaseDB newUser = new FirebaseDB(
                        firebaseUser.getUid(),
                        firebaseUser.getEmail(),
                        Timestamp.now()
                );
                newUser.setUsage_date(getTodayDateString());
                docRef.set(newUser);
            }
        });
    }

    // --- 3. LIMIT KONTROLÜ ---
    public void checkImageLimit(LimitCallback callback) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) {
            callback.onLimitReached("User session not found.");
            return;
        }

        DocumentReference docRef = db.collection(COLLECTION_USERS).document(firebaseUser.getUid());

        db.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(docRef);
            FirebaseDB user = snapshot.toObject(FirebaseDB.class);
            if (user == null) return null;

            String today = getTodayDateString();
            String lastDate = user.getUsage_date();

            // Gün değiştiyse kredileri sıfırla (Kullanıcıyı silmez, sadece update eder)
            if (lastDate == null || !lastDate.equals(today)) {
                transaction.update(docRef, "usage_date", today);
                transaction.update(docRef, "daily_credits", 0);
                user.setDaily_credits(0);
            }

            int currentCredits = user.getDaily_credits();
            boolean isPremium = "premium".equals(user.getPlan_type());
            int limit = isPremium ? 20 : 3;

            if (currentCredits < limit) {
                return "OK";
            } else {
                return isPremium ? "LIMIT_REACHED_PREMIUM" : "LIMIT_REACHED_FREE";
            }

        }).addOnSuccessListener(result -> {
            if (result == null) {
                callback.onLimitReached("User data error.");
            } else if (result.equals("OK")) {
                callback.onSuccess();
            } else {
                callback.onLimitReached(result);
            }
        }).addOnFailureListener(e -> callback.onLimitReached("Error: " + e.getMessage()));
    }

    // --- 4. AKILLI SAYAÇ ---
    public void incrementUsage(String type, long sizeOrLength) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) return;

        DocumentReference docRef = db.collection(COLLECTION_USERS).document(firebaseUser.getUid());

        int timeSavedToAdd = calculateSmartSavedTime(type, sizeOrLength);

        db.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(docRef);
            if (snapshot.exists()) {
                transaction.update(docRef, "daily_credits", FieldValue.increment(1));
                transaction.update(docRef, "total_summaries", FieldValue.increment(1));
                transaction.update(docRef, "total_saved_time", FieldValue.increment(timeSavedToAdd));
            }
            return null;
        });
    }

    private int calculateSmartSavedTime(String type, long sizeOrLength) {
        int savedMinutes = 1;
        if (type == null) type = "TEXT";
        switch (type) {
            case "TEXT":
            case "LINK":
                if (sizeOrLength > 0) savedMinutes = (int) (sizeOrLength / 600);
                if (savedMinutes < 1) savedMinutes = 1;
                break;
            case "AUDIO":
                long sizeInMbAudio = sizeOrLength / (1024 * 1024);
                if (sizeInMbAudio < 1) savedMinutes = 2;
                else savedMinutes = (int) sizeInMbAudio + 2;
                break;
            case "DOC":
                long sizeInMbDoc = sizeOrLength / (1024 * 1024);
                if (sizeInMbDoc < 1) savedMinutes = 5;
                else savedMinutes = (int) (sizeInMbDoc * 3) + 5;
                break;
            case "IMAGE":
                savedMinutes = 3;
                break;
            default:
                savedMinutes = 2;
        }
        return savedMinutes;
    }

    // --- 5. MESAJ KAYDETME ---
    public void saveMessage(String chatId, MessageModel message) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) return;

        MessageModel dbMessage = new MessageModel(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getType(),
                message.getTimestamp()
        );
        dbMessage.setImageUrl(message.getImageUrl());
        dbMessage.setAudioUrl(message.getAudioUrl());
        dbMessage.setDocUrl(message.getDocUrl());
        dbMessage.setFileName(message.getFileName());

        db.collection(COLLECTION_USERS)
                .document(firebaseUser.getUid())
                .collection("chats")
                .document(chatId)
                .collection("messages")
                .document(message.getId())
                .set(dbMessage);
    }

    public void updateChatPreview(String chatId, String lastMessage, String type, Timestamp timestamp) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) return;

        String title = lastMessage.length() > 30 ? lastMessage.substring(0, 30) + "..." : lastMessage;
        MessageModel preview = new MessageModel(chatId, "history", title, type, timestamp);

        db.collection(COLLECTION_USERS)
                .document(firebaseUser.getUid())
                .collection("chats") // Sadece chats koleksiyonuna yazıyor
                .document(chatId)
                .set(preview);
    }

    public void getUserChats(ChatListCallback callback) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) return;

        db.collection(COLLECTION_USERS)
                .document(firebaseUser.getUid())
                .collection("chats")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<MessageModel> list = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        list.add(doc.toObject(MessageModel.class));
                    }
                    callback.onSuccess(list);
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    // 🔥🔥🔥 TEKLİ SİLME (GÜVENLİ) 🔥🔥🔥
    public void deleteChat(String chatId, DeleteCallback callback) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) {
            callback.onError("User not authenticated");
            return;
        }

        // SADECE CHAT REFERANSI
        DocumentReference chatRef = db.collection(COLLECTION_USERS)
                .document(firebaseUser.getUid())
                .collection("chats")
                .document(chatId);

        chatRef.collection("messages").get().addOnCompleteListener(task -> {
            WriteBatch batch = db.batch();

            if (task.isSuccessful() && task.getResult() != null) {
                for (DocumentSnapshot doc : task.getResult()) {
                    MessageModel msg = doc.toObject(MessageModel.class);
                    if (msg != null) deleteMediaIfExists(msg);
                    batch.delete(doc.getReference());
                }
            }
            batch.delete(chatRef);
            batch.commit()
                    .addOnSuccessListener(aVoid -> callback.onSuccess())
                    .addOnFailureListener(e -> callback.onError(e.getMessage()));
        });
    }

    // 🔥🔥🔥 TOPLU SİLME (500+ LİMİTİNE TAKILMAYAN VERSİYON) 🔥🔥🔥
    public void deleteAllHistory(DeleteCallback callback) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) {
            callback.onError("User not found");
            return;
        }

        CollectionReference chatsCollection = db.collection(COLLECTION_USERS)
                .document(firebaseUser.getUid())
                .collection("chats");

        // 1. Önce tüm sohbetleri çek
        chatsCollection.get().addOnSuccessListener(chatSnapshots -> {
            if (chatSnapshots.isEmpty()) {
                callback.onSuccess();
                return;
            }

            // 2. Her sohbetin içindeki mesajları çekmek için Task listesi oluştur
            List<Task<QuerySnapshot>> tasks = new ArrayList<>();
            for (DocumentSnapshot chatDoc : chatSnapshots) {
                tasks.add(chatDoc.getReference().collection("messages").get());
            }

            // 3. Tüm mesajlar geldikten sonra silme listesini hazırla
            Tasks.whenAllSuccess(tasks).addOnSuccessListener(results -> {

                // Silinecek tüm referansları bu listede topluyoruz
                List<DocumentReference> allRefsToDelete = new ArrayList<>();

                // A. Mesajları listeye ekle ve Medyaları sil
                for (Object obj : results) {
                    QuerySnapshot msgSnapshot = (QuerySnapshot) obj;
                    for (DocumentSnapshot msgDoc : msgSnapshot) {
                        MessageModel msg = msgDoc.toObject(MessageModel.class);
                        if (msg != null) deleteMediaIfExists(msg); // Storage'dan sil
                        allRefsToDelete.add(msgDoc.getReference()); // Listeye ekle
                    }
                }

                // B. Sohbet başlıklarını listeye ekle
                for (DocumentSnapshot chatDoc : chatSnapshots) {
                    allRefsToDelete.add(chatDoc.getReference());
                }

                // 4. BATCH CHUNKING (Parçalara Bölme)
                // Firestore limiti: 500 işlem. Biz her 450'de bir yeni paket yapacağız (Garanti olsun).
                int batchSize = 450;
                List<Task<Void>> batchTasks = new ArrayList<>();

                for (int i = 0; i < allRefsToDelete.size(); i += batchSize) {
                    WriteBatch batch = db.batch();

                    int end = Math.min(i + batchSize, allRefsToDelete.size());
                    List<DocumentReference> subList = allRefsToDelete.subList(i, end);

                    for (DocumentReference ref : subList) {
                        batch.delete(ref);
                    }

                    // Bu paketi (Chunk) kuyruğa ekle
                    batchTasks.add(batch.commit());
                }

                // 5. Tüm paketler başarıyla silindiğinde haber ver
                Tasks.whenAll(batchTasks)
                        .addOnSuccessListener(aVoid -> callback.onSuccess())
                        .addOnFailureListener(e -> callback.onError("Partial delete error: " + e.getMessage()));

            }).addOnFailureListener(e -> callback.onError("Fetch error: " + e.getMessage()));

        }).addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    private void deleteMediaIfExists(MessageModel msg) {
        if (msg.getImageUrl() != null) deleteFileFromStorage(msg.getImageUrl());
        if (msg.getAudioUrl() != null) deleteFileFromStorage(msg.getAudioUrl());
        if (msg.getDocUrl() != null) deleteFileFromStorage(msg.getDocUrl());
    }

    private void deleteFileFromStorage(String url) {
        try {
            StorageReference photoRef = storage.getReferenceFromUrl(url);
            photoRef.delete();
        } catch (Exception e) {
        }
    }

    public void getMessages(String chatId, MessagesCallback callback) {
        FirebaseUser firebaseUser = FirebaseAuth.getInstance().getCurrentUser();
        if (firebaseUser == null) return;

        db.collection(COLLECTION_USERS)
                .document(firebaseUser.getUid())
                .collection("chats")
                .document(chatId)
                .collection("messages")
                .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.ASCENDING)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<MessageModel> list = new ArrayList<>();
                    for (DocumentSnapshot doc : queryDocumentSnapshots) {
                        list.add(doc.toObject(MessageModel.class));
                    }
                    callback.onSuccess(list);
                })
                .addOnFailureListener(e -> callback.onError(e.getMessage()));
    }

    private String getTodayDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public interface MessagesCallback {
        void onSuccess(List<MessageModel> messages);
        void onError(String error);
    }

    public interface ChatListCallback {
        void onSuccess(List<MessageModel> chatList);
        void onError(String error);
    }

    public interface LimitCallback {
        void onSuccess();
        void onLimitReached(String message);
    }

    public interface DeleteCallback {
        void onSuccess();
        void onError(String error);
    }

    public interface StorageCallback {
        void onSuccess(String mediaUrl);
        void onError(String error);
    }
}