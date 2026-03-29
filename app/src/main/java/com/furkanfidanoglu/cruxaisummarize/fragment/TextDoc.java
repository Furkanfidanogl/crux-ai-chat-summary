package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.furkanfidanoglu.cruxaisummarize.R;
import com.furkanfidanoglu.cruxaisummarize.adapter.ChatAdapter;
import com.furkanfidanoglu.cruxaisummarize.data.model.MessageModel;
import com.furkanfidanoglu.cruxaisummarize.databinding.FragmentTextDocBinding;
import com.furkanfidanoglu.cruxaisummarize.network.GeminiClient;
import com.furkanfidanoglu.cruxaisummarize.permission.AudioPermission;
import com.furkanfidanoglu.cruxaisummarize.permission.CameraPermission;
import com.furkanfidanoglu.cruxaisummarize.permission.DataPermission;
import com.furkanfidanoglu.cruxaisummarize.permission.DocPermission;
import com.furkanfidanoglu.cruxaisummarize.permission.GalleryPermission;
import com.furkanfidanoglu.cruxaisummarize.util.managers.FirebaseDBManager;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.ImageUtil;
import com.furkanfidanoglu.cruxaisummarize.util.managers.SessionManager;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.WebScraper;
import com.google.firebase.Timestamp;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static android.app.Activity.RESULT_OK;

public class TextDoc extends Fragment {
    private FragmentTextDocBinding binding;
    private final ChatAdapter adapter = new ChatAdapter();
    private GeminiClient geminiClient;
    private FirebaseDBManager dbManager;
    private SessionManager sessionManager;

    // Helper Classes
    private GalleryPermission textDocPermission;
    private AudioPermission audioPermission;
    private DocPermission docPermission;
    private DataPermission dataPermission;

    // Flags & Data
    private boolean isLoading = false;
    private boolean isLinkRequest = false;
    private String currentChatId;

    // Selected Media Data
    private byte[] selectedImageBytes = null;
    private Uri currentPhotoUri;
    private byte[] selectedAudioBytes = null;
    private byte[] selectedDocBytes = null;
    private String selectedFileName = null;
    private String extractedDocText = null;
    private String extractedDataText = null;

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    try {
                        if (currentPhotoUri != null && isAdded()) {
                            InputStream inputStream = requireActivity().getContentResolver().openInputStream(currentPhotoUri);
                            ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
                            int bufferSize = 1024;
                            byte[] buffer = new byte[bufferSize];
                            int len;
                            while ((len = inputStream.read(buffer)) != -1) {
                                byteBuffer.write(buffer, 0, len);
                            }
                            byte[] fullBytes = byteBuffer.toByteArray();

                            resetMediaSelections();
                            selectedImageBytes = ImageUtil.processImage(fullBytes);

                            if (binding != null) {
                                binding.etMessage.setHint(getString(R.string.msg_photo_captured));
                            }
                            Toast.makeText(getContext(), getString(R.string.msg_photo_ready), Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(getContext(), getString(R.string.error_read_file), Toast.LENGTH_SHORT).show();
                    }
                }
            }
    );

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentTextDocBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        geminiClient = GeminiClient.getInstance(requireContext());
        dbManager = FirebaseDBManager.getInstance();
        sessionManager = SessionManager.getInstance();

        // 🔥 HAYAT KURTARAN HAMLE: Fragment açıldığında yükleniyor modunu sıfırla.
        // Böylece kullanıcı geri döndüğünde butonlar kilitli kalmaz.
        setLoadingState(false);

        setupRecyclerView();
        setupSessionManagement(getArguments());
        setupPermissions();
        setupClickListeners();
        setupBackPressHandler();
    }

    private void setupBackPressHandler() {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding != null && binding.layoutAttachments.getVisibility() == View.VISIBLE) {
                    hideAttachmentMenu();
                } else if (adapter.getItemCount() > 0) {
                    startNewChatInternal();
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void setupRecyclerView() {
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(true);
        binding.chatRecyclerView.setLayoutManager(lm);
        binding.chatRecyclerView.setAdapter(adapter);
        binding.chatRecyclerView.setOnTouchListener((v, event) -> false);
    }

    private void setupSessionManagement(Bundle args) {
        String incomingId = (args != null) ? args.getString("chatId") : null;
        String activeSessionId = sessionManager.getActiveChatId();
        if (incomingId != null) {
            if (!incomingId.equals(activeSessionId)) {
                geminiClient.resetChat();
                currentChatId = incomingId;
                loadOldMessages(currentChatId);
                args.remove("chatId");
            } else {
                currentChatId = activeSessionId;
                restoreSessionToUI();
            }
        } else {
            if (activeSessionId != null) {
                currentChatId = activeSessionId;
                restoreSessionToUI();
            } else {
                startNewChatInternal();
            }
        }
    }

    private void setupPermissions() {
        textDocPermission = new GalleryPermission(this, (imageBytes, uri) -> {
            if (imageBytes != null && binding != null) {
                resetMediaSelections();
                selectedImageBytes = ImageUtil.processImage(imageBytes);
                binding.etMessage.setHint(getString(R.string.hint_type_message));
                Toast.makeText(getContext(), getString(R.string.msg_image_selected), Toast.LENGTH_SHORT).show();
            }
        });

        audioPermission = new AudioPermission(this, (audioBytes, fileName) -> {
            if (audioBytes != null && binding != null) {
                resetMediaSelections();
                selectedAudioBytes = audioBytes;
                selectedFileName = fileName;

                binding.etMessage.setHint(fileName);
                binding.etMessage.setText("");
                Toast.makeText(getContext(), getString(R.string.msg_file_uploaded, fileName), Toast.LENGTH_SHORT).show();
            }
        });

        docPermission = new DocPermission(this, (docBytes, fileName) -> {
            if (docBytes != null && binding != null) {

                if (docBytes.length > 8 * 1024 * 1024) {
                    Toast.makeText(getContext(), getString(R.string.msg_file_large), Toast.LENGTH_SHORT).show();
                    return;
                }

                resetMediaSelections();
                selectedFileName = fileName;
                binding.etMessage.setHint(fileName);
                binding.etMessage.setText("");

                // Eğer DOCX ise Apache POI ile okumak için Thread başlatıyoruz
                if (fileName.toLowerCase().endsWith(".docx")) {
                    Toast.makeText(getContext(), getString(R.string.msg_reading_word), Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String text = readDocxFile(docBytes);

                        if (isAdded() && getActivity() != null) {
                            getActivity().runOnUiThread(() ->
                                    handleParsedTextResult(text, getString(R.string.msg_word_ready))
                            );
                        }
                    }).start();
                } else {
                    // PDF veya TXT ise byte olarak tutuyoruz, işlenmeye hazır bekliyor
                    selectedDocBytes = docBytes;
                    Toast.makeText(getContext(), fileName, Toast.LENGTH_SHORT).show();
                }
            }
        });

        dataPermission = new DataPermission(this, (fileBytes, fileName) -> {
            if (fileBytes != null && binding != null) {
                // 🔥 LİMİT GÜNCELLEMESİ: 512 KB (512 * 1024)
                if (fileBytes.length > 512 * 1024) {
                    Toast.makeText(getContext(), getString(R.string.msg_dataset_large), Toast.LENGTH_SHORT).show();
                    return;
                }

                resetMediaSelections();
                selectedFileName = fileName;
                binding.etMessage.setHint(fileName);
                binding.etMessage.setText("");

                Toast.makeText(getContext(), getString(R.string.msg_reading_data), Toast.LENGTH_SHORT).show();

                new Thread(() -> {
                    String text = null;
                    if (fileName.toLowerCase().endsWith(".csv")) {
                        text = readCsvFile(fileBytes);
                    } else if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
                        text = readExcelFile(fileBytes);
                    }

                    if (text != null && !text.isEmpty()) {
                        // Burada veri çok uzunsa kırpma yapıyorsun, aynen kalsın
                        if (text.length() > 100000) {
                            text = text.substring(0, 100000) + "\n...[DATA TRUNCATED]...";
                        }
                        extractedDataText = text;
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(getContext(), getString(R.string.msg_data_ready), Toast.LENGTH_SHORT).show()
                            );
                        }
                    } else {
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() ->
                                    Toast.makeText(getContext(), getString(R.string.error_read_file), Toast.LENGTH_SHORT).show()
                            );
                        }
                    }
                }).start();
            }
        });
    }

    private void handleParsedTextResult(String text, String successMsg) {
        if (!isAdded()) return;
        if (text != null && !text.isEmpty()) {
            extractedDocText = text;
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), successMsg, Toast.LENGTH_SHORT).show()
            );
        } else {
            requireActivity().runOnUiThread(() ->
                    Toast.makeText(getContext(), getString(R.string.error_read_file), Toast.LENGTH_SHORT).show()
            );
        }
    }

    private void setupClickListeners() {
        binding.etMessage.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5000)});

        binding.btnAttach.setOnClickListener(v -> {
            if (!isLoading) toggleAttachmentMenu();
        });

        binding.btnSend.setOnClickListener(v -> {
            if (isLoading) return;
            hideAttachmentMenu();
            String message = binding.etMessage.getText().toString().trim();

            boolean hasMedia = selectedImageBytes != null || selectedAudioBytes != null || selectedDocBytes != null;
            boolean hasTextData = extractedDocText != null || extractedDataText != null;

            if (message.isEmpty() && !hasMedia && !hasTextData) return;

            if (!hasMedia && !hasTextData) {
                if (containsLink(message)) {
                    Toast.makeText(getContext(), getString(R.string.msg_use_link_option), Toast.LENGTH_LONG).show();
                    return;
                }
            }
            isLinkRequest = false;
            handleSendLogic(message);
        });

        binding.menuGallery.setOnClickListener(v -> {
            hideAttachmentMenu();
            dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                @Override
                public void onSuccess() {
                    textDocPermission.checkPermissionsAndOpenGallery();
                }

                @Override
                public void onLimitReached(String message) {
                    Toast.makeText(getContext(), R.string.limit_reached_msg_free, Toast.LENGTH_SHORT).show();
                }
            });
        });

        binding.menuDocument.setOnClickListener(v -> {
            hideAttachmentMenu();
            dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                @Override
                public void onSuccess() {
                    docPermission.checkPermissionsAndOpenPicker();
                }

                @Override
                public void onLimitReached(String message) {
                    Toast.makeText(getContext(), R.string.limit_reached_msg_free, Toast.LENGTH_SHORT).show();
                }
            });
        });

        binding.menuAudio.setOnClickListener(v -> {
            hideAttachmentMenu();
            dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                @Override
                public void onSuccess() {
                    audioPermission.checkPermissionsAndOpenPicker();
                }

                @Override
                public void onLimitReached(String message) {
                    Toast.makeText(getContext(), R.string.limit_reached_msg_free, Toast.LENGTH_SHORT).show();
                }
            });
        });

        binding.menuData.setOnClickListener(v -> {
            hideAttachmentMenu();
            dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                @Override
                public void onSuccess() {
                    dataPermission.checkPermissionsAndOpenPicker();
                }

                @Override
                public void onLimitReached(String message) {
                    Toast.makeText(getContext(), R.string.limit_reached_msg_free, Toast.LENGTH_SHORT).show();
                }
            });
        });

        binding.menuCamera.setOnClickListener(v -> {
            hideAttachmentMenu();

            dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                @Override
                public void onSuccess() {
                    // Hakkı varsa izin kontrolü yap ve kamerayı aç
                    if (CameraPermission.hasCameraPermission(getContext())) {
                        openCamera();
                    } else {
                        requestPermissions(new String[]{android.Manifest.permission.CAMERA}, CameraPermission.CAMERA_PERMISSION_CODE);
                    }
                }

                @Override
                public void onLimitReached(String message) {
                    Toast.makeText(getContext(), R.string.limit_reached_msg_free, Toast.LENGTH_SHORT).show();
                }
            });
        });

        binding.menuLink.setOnClickListener(v -> {
            hideAttachmentMenu();

            // 🔥 Önce Limit Kontrolü
            dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                @Override
                public void onSuccess() {
                    // Hakkı varsa Link penceresini aç
                    showLinkBottomSheet();
                }

                @Override
                public void onLimitReached(String message) {
                    // Hakkı yoksa "Limit Doldu" mesajı ver, pencereyi açma
                    Toast.makeText(getContext(), R.string.limit_reached_msg_free, Toast.LENGTH_SHORT).show();
                }
            });
        });

        binding.btnNewChat.setOnClickListener(v -> startNewChatInternal());

        binding.chipCanDo.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (!isLoading) handleSendLogic(getString(R.string.msg_prompt_capabilities));
        });
        binding.chipImage.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (!isLoading) handleSendLogic(getString(R.string.msg_prompt_audio));
        });
        binding.chipPDF.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (!isLoading) handleSendLogic(getString(R.string.msg_prompt_website));
        });

        binding.etMessage.setOnClickListener(v -> hideAttachmentMenu());
        binding.etMessage.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) hideAttachmentMenu();
        });


    }

    private void openCamera() {
        if (getActivity() == null) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.TITLE, "New Picture");
        values.put(MediaStore.Images.Media.DESCRIPTION, "From Camera");
        currentPhotoUri = requireActivity().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (currentPhotoUri == null) {
            Toast.makeText(getContext(), getString(R.string.msg_camera_error), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri);
        try {
            cameraLauncher.launch(cameraIntent);
        } catch (Exception e) {
            Toast.makeText(getContext(), getString(R.string.msg_camera_error), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CameraPermission.CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                Toast.makeText(getContext(), getString(R.string.perm_denied), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showLinkBottomSheet() {
        LinkBottomSheetFragment bottomSheet = new LinkBottomSheetFragment();
        bottomSheet.setListener(url -> {
            isLinkRequest = true;
            handleSendLogic(url);
        });
        bottomSheet.show(getParentFragmentManager(), "LinkBottomSheet");
    }

    private void handleSendLogic(String messageText) {
        String tempMsgId = String.valueOf(System.currentTimeMillis());
        MessageModel userMessage;

        if (messageText.toLowerCase().contains("youtube.com") || messageText.toLowerCase().contains("youtu.be")) {
            Toast.makeText(getContext(), getString(R.string.msg_youtube_error), Toast.LENGTH_LONG).show();
            setLoadingState(false);
            return;
        }

        if (selectedImageBytes != null) {
            userMessage = new MessageModel(tempMsgId, "user", messageText, "IMAGE", Timestamp.now());
            userMessage.setImage(selectedImageBytes);
        } else if (selectedAudioBytes != null) {
            String fName = (selectedFileName != null) ? selectedFileName : "Audio";
            String finalContent = messageText.isEmpty() ? fName : messageText + "\n" + fName;
            userMessage = new MessageModel(tempMsgId, "user", finalContent, "AUDIO", Timestamp.now());
            userMessage.setAudio(selectedAudioBytes);
            userMessage.setFileName(selectedFileName);
        } else if (selectedDocBytes != null) {
            String fName = (selectedFileName != null) ? selectedFileName : "Document";
            String finalContent = messageText.isEmpty() ? fName : messageText + "\n" + fName;
            userMessage = new MessageModel(tempMsgId, "user", finalContent, "DOC", Timestamp.now());
            userMessage.setDoc(selectedDocBytes);
            userMessage.setFileName(selectedFileName);
        } else if (extractedDocText != null) {
            String fName = (selectedFileName != null) ? selectedFileName : "Word Document";
            String displayContent = messageText.isEmpty() ? fName : messageText + "\n" + fName;
            userMessage = new MessageModel(tempMsgId, "user", displayContent, "DOC", Timestamp.now());
        } else if (extractedDataText != null) {
            String fName = (selectedFileName != null) ? selectedFileName : "Data File";
            String displayContent = messageText.isEmpty() ? fName : messageText + "\n" + fName;
            userMessage = new MessageModel(tempMsgId, "user", displayContent, "DOC", Timestamp.now());
        } else {
            userMessage = new MessageModel(tempMsgId, "user", messageText, "TEXT", Timestamp.now());
        }

        adapter.addMessage(userMessage);
        sessionManager.addMessage(userMessage);
        updateEmptyState();
        scrollToBottom();
        setLoadingState(true);

        binding.etMessage.setHint(getString(R.string.hint_type_message));
        binding.etMessage.setText("");

        if (selectedImageBytes != null) {
            final byte[] media = selectedImageBytes;
            selectedImageBytes = null;
            checkLimitAndUpload(userMessage, media, "IMAGE");
        } else if (selectedAudioBytes != null) {
            final byte[] media = selectedAudioBytes;
            selectedAudioBytes = null;
            selectedFileName = null;
            checkLimitAndUpload(userMessage, media, "AUDIO");
        } else if (selectedDocBytes != null) {
            final byte[] media = selectedDocBytes;
            selectedDocBytes = null;
            selectedFileName = null;
            checkLimitAndUpload(userMessage, media, "DOC");
        } else if (extractedDocText != null) {
            String fullPrompt = getString(R.string.msg_docx_prompt) + extractedDocText + "\n\nUser: " + messageText;
            extractedDocText = null;
            selectedFileName = null;
            sendMessageToGeminiInternal(userMessage, null, "TEXT", true, fullPrompt);
        } else if (extractedDataText != null) {
            String fullPrompt = getString(R.string.msg_data_prompt) + extractedDataText + "\n\nUSER QUESTION: " + messageText;
            extractedDataText = null;
            selectedFileName = null;
            sendMessageToGeminiInternal(userMessage, null, "TEXT", true, fullPrompt);
        } else {
            if (isLinkRequest) {
                dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
                    @Override
                    public void onSuccess() {
                        processWebLink(userMessage);
                        isLinkRequest = false;
                    }

                    @Override
                    public void onLimitReached(String message) {
                        handleQuotaError(message);
                    }
                });
            } else {
                sendMessageToGeminiInternal(userMessage, null, "TEXT", false, null);
            }
        }
    }

    private boolean containsLink(String text) {
        if (text == null) return false;

        String lower = text.toLowerCase();

        // 🛡️ 1. MAIL KORUMASI (ÖNEMLİ)
        // Eğer içinde '@' varsa bu muhtemelen bir mail adresidir.
        // Mail adreslerini link modülüne zorlamamalıyız.
        if (lower.contains("@")) return false;

        // 🚨 2. KESİN LİNK BELİRTİLERİ
        if (lower.contains("http://") ||
                lower.contains("https://") ||
                lower.contains("www.")) {
            return true;
        }

        // 🌐 3. UZANTI KONTROLÜ (Naked Domainler: google.com, site.net)
        // Buraya en yaygın uzantıları ekledik.
        // "1.oyuncu" buna takılmaz çünkü bu uzantılar yok.
        return lower.contains(".com") ||
                lower.contains(".net") ||
                lower.contains(".org") ||
                lower.contains(".edu") ||
                lower.contains(".gov") ||
                lower.contains(".io") ||
                lower.contains(".ai") ||
                lower.contains(".tr");
    }

    private String readDocxFile(byte[] fileBytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes);
             XWPFDocument document = new XWPFDocument(inputStream)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : document.getParagraphs()) {
                sb.append(para.getText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String readCsvFile(byte[] fileBytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String readExcelFile(byte[] fileBytes) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            StringBuilder sb = new StringBuilder();
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                for (Cell cell : row) {
                    sb.append(cell.toString()).append(" | ");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // --- WEB SCRAPING GÜVENLİK AYARI ---
    private void processWebLink(MessageModel userMessage) {
        if (isAdded()) {
            requireActivity().runOnUiThread(() -> {
                MessageModel loadingMsg = new MessageModel("loading", "model", getString(R.string.msg_reading_web), "TEXT", Timestamp.now());
                adapter.addMessage(loadingMsg);
                scrollToBottom();
            });
        }

        WebScraper.scrapeUrl(userMessage.getContent(), new WebScraper.ScrapeCallback() {
            @Override
            public void onSuccess(String cleanContent) {
                String promptPrefix = "Here is the website content. Please analyze it:\n\n";
                // Güvenli Context Erişimi
                if (getContext() != null) {
                    promptPrefix = getString(R.string.msg_web_prompt);
                }

                String finalPrompt = promptPrefix + userMessage.getContent() + "\n\nCONTENT:\n" + cleanContent;

                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> adapter.removeLastItem());
                }

                sendMessageToGeminiInternal(userMessage, null, "LINK", true, finalPrompt);
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    adapter.removeLastItem();
                    setLoadingState(false);
                    Toast.makeText(getContext(), getString(R.string.msg_web_error) + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void checkLimitAndUpload(MessageModel userMessage, byte[] mediaBytes, String type) {
        dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
            @Override
            public void onSuccess() {
                uploadMediaAndSend(userMessage, mediaBytes, type);
            }

            @Override
            public void onLimitReached(String message) {
                handleQuotaError(message);
            }
        });
    }

    // --- UPLOAD GÜVENLİK AYARI ---
    private void uploadMediaAndSend(MessageModel userMessage, byte[] mediaBytes, String type) {
        FirebaseDBManager.StorageCallback callback = new FirebaseDBManager.StorageCallback() {
            @Override
            public void onSuccess(String mediaUrl) {
                // Upload biter bitmez kullanıcı çıksa bile modele URL'i yaz
                if (type.equals("IMAGE")) userMessage.setImageUrl(mediaUrl);
                else if (type.equals("AUDIO")) userMessage.setAudioUrl(mediaUrl);
                else if (type.equals("DOC")) userMessage.setDocUrl(mediaUrl);

                // Ve Gemini'yi tetikle (Burada isAdded() kontrolü YOK, arka planda da çalışmalı)
                sendMessageToGeminiInternal(userMessage, mediaBytes, type, true, null);
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                handleQuotaError(error);
            }
        };

        if (type.equals("IMAGE")) {
            dbManager.uploadImageToStorage(mediaBytes, callback);
        } else {
            String ext = "bin";
            if (userMessage.getFileName() != null && userMessage.getFileName().contains(".")) {
                ext = userMessage.getFileName().substring(userMessage.getFileName().lastIndexOf(".") + 1);
            } else if (type.equals("DOC")) ext = "pdf";
            else if (type.equals("AUDIO")) ext = "mp3";

            dbManager.uploadByteFileToStorage(mediaBytes, ext, callback);
        }
    }

    private void sendMessageToGeminiInternal(MessageModel userMessage, byte[] mediaBytes, String mediaType, boolean consumeQuota, @Nullable String manualPrompt) {

        if (isAdded() && binding != null) {
            if (adapter.getItemCount() > 0 && !adapter.getLastItem().getId().equals("loading")) {
                MessageModel loadingMsg = new MessageModel("loading", "model", "...", "TEXT", Timestamp.now());
                adapter.addMessage(loadingMsg);
                scrollToBottom();
            }
        }

        dbManager.saveMessage(currentChatId, userMessage);
        dbManager.updateChatPreview(currentChatId, userMessage.getContent(), userMessage.getType(), Timestamp.now());

        String contentToSend = (manualPrompt != null) ? manualPrompt : userMessage.getContent();

        geminiClient.sendMessage(contentToSend, mediaBytes, mediaType, new GeminiClient.GeminiCallback() {
            @Override
            public void onSuccess(String response) {
                // 1. ARKA PLAN: Veriyi mutlaka kaydet (Burası Mükemmel) ✅
                MessageModel botMessage = new MessageModel(String.valueOf(System.currentTimeMillis()), "model", response, "TEXT", Timestamp.now());

                sessionManager.addMessage(botMessage);
                dbManager.saveMessage(currentChatId, botMessage);
                dbManager.updateChatPreview(currentChatId, response, "TEXT", Timestamp.now());

                if (consumeQuota) {
                    long size = (mediaBytes != null) ? mediaBytes.length : contentToSend.length();
                    dbManager.incrementUsage(mediaType, size);
                }

                // 2. ÖN YÜZ: UI Güncellemesi
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        adapter.removeLastItem(); // Loading'i sil
                        adapter.addMessage(botMessage); // Mesajı ekle
                        scrollToBottom();
                        updateEmptyState();
                        setLoadingState(false);
                    });
                }
            }

            @Override
            public void onError(Throwable t) {
                if (isAdded() && getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        // Loading balonunu sil (Varsa)
                        if (adapter.getItemCount() > 0 && adapter.getLastItem().getId().equals("loading")) {
                            adapter.removeLastItem();
                        }

                        String displayError = t.getMessage();
                        if (displayError == null || displayError.isEmpty()) {
                            displayError = getString(R.string.error_unknown);
                        }

                        Toast.makeText(getContext(), displayError, Toast.LENGTH_LONG).show();

                        setLoadingState(false);
                    });
                }
            }
        });
    }

    private void handleQuotaError(String message) {
        if (!isAdded()) return;

        if (adapter.getItemCount() > 0 && adapter.getLastItem().getId().equals("loading"))
            adapter.removeLastItem();

        if (adapter.getItemCount() > 0 && adapter.getLastItem().getRole().equals("user")) {
            adapter.removeLastItem();
            sessionManager.removeLastMessage();
        }

        updateEmptyState();
        setLoadingState(false);
        isLinkRequest = false;

        if ("LIMIT_REACHED_FREE".equals(message)) {
            Toast.makeText(getContext(), getString(R.string.limit_reached_msg_free), Toast.LENGTH_LONG).show();
        } else if ("LIMIT_REACHED_PREMIUM".equals(message)) {
            Toast.makeText(getContext(), getString(R.string.limit_reached_msg_premium), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        }
    }

    private void startNewChatInternal() {
        if (!isAdded()) return;
        Toast.makeText(getContext(), R.string.new_chat, Toast.LENGTH_SHORT).show();
        adapter.clearMessages();
        sessionManager.clearSession();
        geminiClient.resetChat();
        resetMediaSelections();
        currentChatId = "chat_" + System.currentTimeMillis();
        sessionManager.setActiveChatId(currentChatId);
        setLoadingState(false);
        updateEmptyState();
        if (getArguments() != null) getArguments().remove("chatId");
    }

    private void restoreSessionToUI() {
        List<MessageModel> msgs = sessionManager.getActiveMessages();
        if (msgs != null && !msgs.isEmpty()) {
            adapter.setMessages(msgs);
            scrollToBottom();
        }
        updateEmptyState();
    }

    private void loadOldMessages(String chatId) {
        setLoadingState(true);
        adapter.clearMessages();
        dbManager.getMessages(chatId, new FirebaseDBManager.MessagesCallback() {
            @Override
            public void onSuccess(List<MessageModel> messages) {
                if (!isAdded()) return;
                sessionManager.setActiveChatId(chatId);
                sessionManager.setActiveMessages(messages);
                adapter.setMessages(messages);
                geminiClient.loadHistory(messages);
                scrollToBottom();
                updateEmptyState();
                setLoadingState(false);
            }

            @Override
            public void onError(String error) {
                if (!isAdded()) return;
                setLoadingState(false);
            }
        });
    }

    private void resetMediaSelections() {
        selectedImageBytes = null;
        selectedAudioBytes = null;
        selectedDocBytes = null;
        selectedFileName = null;
        extractedDocText = null;
        extractedDataText = null;
        isLinkRequest = false;
        if (binding != null) {
            binding.etMessage.setHint(getString(R.string.hint_type_message));
        }
    }

    private void toggleAttachmentMenu() {
        if (binding == null) return;
        if (binding.layoutAttachments.getVisibility() == View.VISIBLE) hideAttachmentMenu();
        else showAttachmentMenu();
    }

    private void showAttachmentMenu() {
        if (binding == null) return;
        hideKeyboard();
        binding.layoutAttachments.setVisibility(View.VISIBLE);
        binding.layoutAttachments.setTranslationY(1000f);
        binding.layoutAttachments.animate().translationY(0).setDuration(300).setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void hideAttachmentMenu() {
        if (binding == null || binding.layoutAttachments.getVisibility() != View.VISIBLE) return;
        binding.layoutAttachments.animate().translationY(1000f).setDuration(300).withEndAction(() -> {
            if (binding != null) binding.layoutAttachments.setVisibility(View.GONE);
        }).start();
    }

    private void hideKeyboard() {
        if (getActivity() == null) return;
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void setLoadingState(boolean loading) {
        if (binding == null) return;
        this.isLoading = loading;
        binding.btnSend.setEnabled(!loading);
        binding.btnAttach.setEnabled(!loading);
        binding.btnAttach.setAlpha(loading ? 0.5f : 1.0f);
        binding.btnSend.setAlpha(loading ? 0.5f : 1.0f);
    }

    private void scrollToBottom() {
        if (binding != null && adapter.getItemCount() > 0)
            binding.chatRecyclerView.smoothScrollToPosition(adapter.getItemCount() - 1);
    }

    private void updateEmptyState() {
        if (binding == null) return;
        if (adapter.getItemCount() == 0) {
            binding.layoutEmptyState.setVisibility(View.VISIBLE);
            binding.chatRecyclerView.setVisibility(View.GONE);
        } else {
            binding.layoutEmptyState.setVisibility(View.GONE);
            binding.chatRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}