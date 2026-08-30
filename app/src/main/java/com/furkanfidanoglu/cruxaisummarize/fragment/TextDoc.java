package com.furkanfidanoglu.cruxaisummarize.fragment;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

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
import com.furkanfidanoglu.cruxaisummarize.permission.DataPermission;
import com.furkanfidanoglu.cruxaisummarize.permission.DocPermission;
import com.furkanfidanoglu.cruxaisummarize.permission.GalleryPermission;
import com.furkanfidanoglu.cruxaisummarize.util.helpers.ContentUriUtil;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    // Typewriter Fields
    private final android.os.Handler typewriterHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable activeTypewriterRunnable = null;
    private MessageModel activeTypewriterMessage = null;
    private String activeTypewriterFullText = null;
    private boolean isUserScrollingUp = false;

    // Selected Media Data
    private byte[] selectedImageBytes;
    private Uri currentPhotoUri;
    private Uri selectedMediaUri;
    private String selectedMediaType;
    private long selectedMediaSize = -1L;
    private String selectedFileName;
    private String extractedDocText;
    private String extractedDataText;
    private ExecutorService mediaExecutor;

    private final ActivityResultLauncher<Uri> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                Uri photoUri = restoreCurrentPhotoUri();
                clearStoredPhotoUri();
                if (photoUri == null) return;

                long capturedSize = isAdded()
                        ? ContentUriUtil.getMetadata(requireContext(), photoUri).size
                        : -1L;
                if (Boolean.TRUE.equals(success) || capturedSize > 0L) {
                    processSelectedImage(photoUri, true);
                } else if (isAdded()) {
                    requireContext().getContentResolver().delete(photoUri, null, null);
                }
            });

    private final ActivityResultLauncher<String> cameraPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (Boolean.TRUE.equals(granted)) {
                    openCamera();
                } else if (isAdded()) {
                    Toast.makeText(requireContext(), R.string.perm_denied, Toast.LENGTH_SHORT).show();
                }
            });

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
        mediaExecutor = Executors.newSingleThreadExecutor();

        sessionManager.setChatCallback(new SessionManager.ChatCallback() {
            @Override
            public void onSuccess(MessageModel botMessage) {
                typewriterHandler.post(() -> {
                    if (isAdded() && getActivity() != null) {
                        adapter.removeLoadingItem();
                        startTypewriterEffect(botMessage, botMessage.getContent());
                        updateEmptyState();
                    }
                });
            }

            @Override
            public void onError(Throwable t) {
                typewriterHandler.post(() -> {
                    if (isAdded() && getActivity() != null) {
                        adapter.removeLoadingItem();
                        String displayError = t.getMessage();
                        if (displayError == null || displayError.isEmpty()) {
                            displayError = getString(R.string.error_unknown);
                        }
                        Toast.makeText(getContext(), displayError, Toast.LENGTH_LONG).show();
                        setLoadingState(false);
                    }
                });
            }
        });

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
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (binding != null && binding.layoutAttachments.getVisibility() == View.VISIBLE) {
                            hideAttachmentMenu();
                        } else if (adapter.getItemCount() > 0) {
                            startNewChatInternal();
                        } else {
                            setEnabled(false); // Sisteme devret
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        }
                    }
                });
    }

    private void setupRecyclerView() {
        LinearLayoutManager lm = new LinearLayoutManager(getContext());
        lm.setStackFromEnd(false);
        binding.chatRecyclerView.setLayoutManager(lm);
        binding.chatRecyclerView.setAdapter(adapter);
        binding.chatRecyclerView.setItemAnimator(null);

        binding.chatRecyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING || newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_SETTLING) {
                    isUserScrollingUp = true;
                } else if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                    LinearLayoutManager layoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
                    if (layoutManager != null) {
                        int lastPos = layoutManager.findLastCompletelyVisibleItemPosition();
                        if (lastPos >= adapter.getItemCount() - 1) {
                            isUserScrollingUp = false;
                        }
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (dy < 0) {
                    isUserScrollingUp = true;
                }
            }
        });
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
        textDocPermission = new GalleryPermission(this, uri -> processSelectedImage(uri, false));

        audioPermission = new AudioPermission(this, (uri, fileName, fileSize) -> {
            if (uri != null && binding != null) {
                resetMediaSelections();
                selectedMediaUri = uri;
                selectedMediaType = "AUDIO";
                selectedMediaSize = fileSize;
                selectedFileName = fileName;

                binding.etMessage.setHint(fileName);
                binding.etMessage.setText("");
                Toast.makeText(getContext(), getString(R.string.msg_file_uploaded, fileName), Toast.LENGTH_SHORT)
                        .show();
            }
        });

        docPermission = new DocPermission(this, (uri, fileName, fileSize) -> {
            if (uri != null && binding != null) {
                resetMediaSelections();
                selectedFileName = fileName;
                binding.etMessage.setHint(fileName);
                binding.etMessage.setText("");

                // Eğer DOCX ise Apache POI ile okumak için Thread başlatıyoruz
                if (fileName.toLowerCase().endsWith(".docx")) {
                    Toast.makeText(getContext(), getString(R.string.msg_reading_word), Toast.LENGTH_SHORT).show();
                    Context appContext = requireContext().getApplicationContext();
                    mediaExecutor.execute(() -> {
                        String text = readDocxFile(appContext, uri);

                        if (isAdded() && getActivity() != null) {
                            getActivity().runOnUiThread(
                                    () -> handleParsedTextResult(text, getString(R.string.msg_word_ready)));
                        }
                    });
                } else {
                    selectedMediaUri = uri;
                    selectedMediaType = "DOC";
                    selectedMediaSize = fileSize;
                    Toast.makeText(getContext(), fileName, Toast.LENGTH_SHORT).show();
                }
            }
        });

        dataPermission = new DataPermission(this, (uri, fileName, fileSize) -> {
            if (uri != null && binding != null) {
                resetMediaSelections();
                selectedFileName = fileName;
                binding.etMessage.setHint(fileName);
                binding.etMessage.setText("");

                Toast.makeText(getContext(), getString(R.string.msg_reading_data), Toast.LENGTH_SHORT).show();

                Context appContext = requireContext().getApplicationContext();
                mediaExecutor.execute(() -> {
                    String text = null;
                    if (fileName.toLowerCase().endsWith(".csv")) {
                        text = readCsvFile(appContext, uri);
                    } else if (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")) {
                        text = readExcelFile(appContext, uri);
                    }

                    if (text != null && !text.isEmpty()) {
                        // Burada veri çok uzunsa kırpma yapıyorsun, aynen kalsın
                        if (text.length() > 100000) {
                            text = text.substring(0, 100000) + "\n...[DATA TRUNCATED]...";
                        }
                        extractedDataText = text;
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() -> Toast
                                    .makeText(getContext(), getString(R.string.msg_data_ready), Toast.LENGTH_SHORT)
                                    .show());
                        }
                    } else {
                        if (isAdded()) {
                            requireActivity().runOnUiThread(() -> Toast
                                    .makeText(getContext(), getString(R.string.error_read_file), Toast.LENGTH_SHORT)
                                    .show());
                            }
                        }
                });
            }
        });
    }

    private void processSelectedImage(Uri uri, boolean fromCamera) {
        if (uri == null || binding == null || mediaExecutor == null) return;
        binding.etMessage.setHint(getString(fromCamera
                ? R.string.msg_processing_photo
                : R.string.msg_processing_image));

        Context appContext = requireContext().getApplicationContext();
        mediaExecutor.execute(() -> {
            byte[] processed = ImageUtil.processImage(appContext, uri);
            if (!isAdded()) return;

            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                if (processed == null || processed.length == 0) {
                    binding.etMessage.setHint(getString(R.string.hint_type_message));
                    Toast.makeText(requireContext(), R.string.error_read_file, Toast.LENGTH_SHORT).show();
                    return;
                }

                resetMediaSelections();
                selectedImageBytes = processed;
                binding.etMessage.setHint(getString(fromCamera
                        ? R.string.msg_photo_captured
                        : R.string.hint_type_message));
                Toast.makeText(requireContext(), fromCamera
                                ? R.string.msg_photo_ready
                                : R.string.msg_image_selected,
                        Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void handleParsedTextResult(String text, String successMsg) {
        if (!isAdded())
            return;
        if (text != null && !text.isEmpty()) {
            extractedDocText = text;
            requireActivity().runOnUiThread(() -> Toast.makeText(getContext(), successMsg, Toast.LENGTH_SHORT).show());
        } else {
            requireActivity().runOnUiThread(
                    () -> Toast.makeText(getContext(), getString(R.string.error_read_file), Toast.LENGTH_SHORT).show());
        }
    }

    private void setupClickListeners() {
        binding.etMessage.setFilters(new InputFilter[] { new InputFilter.LengthFilter(5000) });

        binding.btnAttach.setOnClickListener(v -> {
            if (!isLoading)
                toggleAttachmentMenu();
        });

        binding.btnSend.setOnClickListener(v -> {
            if (isLoading)
                return;
            hideAttachmentMenu();
            String message = binding.etMessage.getText().toString().trim();

            boolean hasMedia = selectedImageBytes != null || selectedMediaUri != null;
            boolean hasTextData = extractedDocText != null || extractedDataText != null;

            if (message.isEmpty() && !hasMedia && !hasTextData)
                return;

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
            textDocPermission.checkPermissionsAndOpenGallery();
        });

        binding.menuDocument.setOnClickListener(v -> {
            hideAttachmentMenu();
            docPermission.checkPermissionsAndOpenPicker();
        });

        binding.menuAudio.setOnClickListener(v -> {
            hideAttachmentMenu();
            audioPermission.checkPermissionsAndOpenPicker();
        });

        binding.menuData.setOnClickListener(v -> {
            hideAttachmentMenu();
            dataPermission.checkPermissionsAndOpenPicker();
        });

        binding.menuCamera.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(), android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            }
        });

        binding.menuLink.setOnClickListener(v -> {
            hideAttachmentMenu();
            showLinkBottomSheet();
        });

        binding.btnNewChat.setOnClickListener(v -> startNewChatInternal());

        binding.chipCanDo.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (!isLoading)
                handleSendLogic(getString(R.string.msg_prompt_capabilities));
        });
        binding.chipImage.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (!isLoading)
                handleSendLogic(getString(R.string.msg_prompt_audio));
        });
        binding.chipPDF.setOnClickListener(v -> {
            hideAttachmentMenu();
            if (!isLoading)
                handleSendLogic(getString(R.string.msg_prompt_website));
        });

        binding.etMessage.setOnClickListener(v -> hideAttachmentMenu());
        binding.etMessage.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus)
                hideAttachmentMenu();
        });

    }

    private void openCamera() {
        if (!isAdded()) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "crux_" + System.currentTimeMillis() + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Crux AI");
        }
        currentPhotoUri = requireContext().getContentResolver()
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (currentPhotoUri == null) {
            Toast.makeText(getContext(), getString(R.string.msg_camera_error), Toast.LENGTH_SHORT).show();
            return;
        }

        // Save URI to SharedPreferences in case of Activity recreation
        if (getContext() != null) {
            getContext().getSharedPreferences("CameraPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("currentPhotoUri", currentPhotoUri.toString())
                    .apply();
        }

        try {
            cameraLauncher.launch(currentPhotoUri);
        } catch (Exception e) {
            requireContext().getContentResolver().delete(currentPhotoUri, null, null);
            clearStoredPhotoUri();
            Toast.makeText(getContext(), getString(R.string.msg_camera_error), Toast.LENGTH_SHORT).show();
        }
    }

    private Uri restoreCurrentPhotoUri() {
        if (currentPhotoUri != null) return currentPhotoUri;
        if (!isAdded()) return null;
        String stored = requireContext().getSharedPreferences("CameraPrefs", Context.MODE_PRIVATE)
                .getString("currentPhotoUri", null);
        return stored == null ? null : Uri.parse(stored);
    }

    private void clearStoredPhotoUri() {
        currentPhotoUri = null;
        if (isAdded()) {
            requireContext().getSharedPreferences("CameraPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("currentPhotoUri")
                    .apply();
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
        } else if (selectedMediaUri != null && "AUDIO".equals(selectedMediaType)) {
            String fName = (selectedFileName != null) ? selectedFileName : "Audio";
            String finalContent = messageText.isEmpty() ? fName : messageText + "\n" + fName;
            userMessage = new MessageModel(tempMsgId, "user", finalContent, "AUDIO", Timestamp.now());
            userMessage.setFileName(selectedFileName);
        } else if (selectedMediaUri != null && "DOC".equals(selectedMediaType)) {
            String fName = (selectedFileName != null) ? selectedFileName : "Document";
            String finalContent = messageText.isEmpty() ? fName : messageText + "\n" + fName;
            userMessage = new MessageModel(tempMsgId, "user", finalContent, "DOC", Timestamp.now());
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
        sessionManager.setAILoading(true); // AI loading starts now!

        // 🔥 YENİ: Anında typing balonunu ekle (Böylece Storage upload süresi boyunca da görünür)
        MessageModel loadingMsg = new MessageModel("loading", "model", "...", "TEXT", Timestamp.now());
        adapter.addMessage(loadingMsg);
        scrollToBottom();

        binding.etMessage.setHint(getString(R.string.hint_type_message));
        binding.etMessage.setText("");

        if (selectedImageBytes != null) {
            final byte[] media = selectedImageBytes;
            selectedImageBytes = null;
            checkLimitAndUpload(userMessage, media, "IMAGE");
        } else if (selectedMediaUri != null && "AUDIO".equals(selectedMediaType)) {
            Uri mediaUri = selectedMediaUri;
            long mediaSize = selectedMediaSize;
            selectedMediaUri = null;
            selectedMediaType = null;
            selectedMediaSize = -1L;
            selectedFileName = null;
            checkLimitAndUpload(userMessage, mediaUri, mediaSize, "AUDIO");
        } else if (selectedMediaUri != null && "DOC".equals(selectedMediaType)) {
            Uri mediaUri = selectedMediaUri;
            long mediaSize = selectedMediaSize;
            selectedMediaUri = null;
            selectedMediaType = null;
            selectedMediaSize = -1L;
            selectedFileName = null;
            checkLimitAndUpload(userMessage, mediaUri, mediaSize, "DOC");
        } else if (extractedDocText != null) {
            String fullPrompt = getString(R.string.msg_docx_prompt) + extractedDocText + "\n\nUser: " + messageText;
            extractedDocText = null;
            selectedFileName = null;
            checkLimitAndSendExtractedText(userMessage, fullPrompt);
        } else if (extractedDataText != null) {
            String fullPrompt = getString(R.string.msg_data_prompt) + extractedDataText + "\n\nUSER QUESTION: "
                    + messageText;
            extractedDataText = null;
            selectedFileName = null;
            checkLimitAndSendExtractedText(userMessage, fullPrompt);
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
                sendMessageToGeminiInternal(userMessage, 0L, "TEXT", false, null);
            }
        }
    }

    private boolean containsLink(String text) {
        if (text == null)
            return false;

        String lower = text.toLowerCase();

        // 🛡️ 1. MAIL KORUMASI (ÖNEMLİ)
        if (lower.contains("@"))
            return false;

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

    private String readDocxFile(Context context, Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                XWPFDocument document = inputStream == null ? null : new XWPFDocument(inputStream)) {
            if (document == null) return null;
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : document.getParagraphs()) {
                sb.append(para.getText()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String readCsvFile(Context context, Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                BufferedReader reader = inputStream == null ? null : new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            if (reader == null) return null;
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

    private String readExcelFile(Context context, Uri uri) {
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri);
                Workbook workbook = inputStream == null ? null : new XSSFWorkbook(inputStream)) {
            if (workbook == null) return null;
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
                MessageModel loadingMsg = new MessageModel("loading", "model", getString(R.string.msg_reading_web),
                        "TEXT", Timestamp.now());
                adapter.addMessage(loadingMsg);
                scrollToBottom();
            });
        }

        WebScraper.scrapeUrl(userMessage.getContent(), new WebScraper.ScrapeCallback() {
            @Override
            public void onSuccess(String cleanContent) {
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        String promptPrefix = getString(R.string.msg_web_prompt);
                        String finalPrompt = promptPrefix + userMessage.getContent() + "\n\nCONTENT:\n" + cleanContent;
                        adapter.removeLoadingItem();
                        sendMessageToGeminiInternal(userMessage, 0L, "LINK", true, finalPrompt);
                    }
                });
            }

            @Override
            public void onError(String error) {
                sessionManager.setAILoading(false);
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        adapter.removeLoadingItem();
                        setLoadingState(false);
                        Toast.makeText(getContext(), getString(R.string.msg_web_error) + error, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void checkLimitAndUpload(MessageModel userMessage, byte[] mediaBytes, String type) {
        dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
            @Override
            public void onSuccess() {
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        uploadMediaAndSend(userMessage, mediaBytes, type);
                    }
                });
            }

            @Override
            public void onLimitReached(String message) {
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        handleQuotaError(message);
                    }
                });
            }
        });
    }

    private void checkLimitAndSendExtractedText(MessageModel userMessage, String fullPrompt) {
        dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
            @Override
            public void onSuccess() {
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        sendMessageToGeminiInternal(userMessage, 0L, "TEXT", true, fullPrompt);
                    }
                });
            }

            @Override
            public void onLimitReached(String message) {
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        handleQuotaError(message);
                    }
                });
            }
        });
    }

    private void checkLimitAndUpload(MessageModel userMessage, Uri mediaUri, long mediaSize, String type) {
        dbManager.checkImageLimit(new FirebaseDBManager.LimitCallback() {
            @Override
            public void onSuccess() {
                typewriterHandler.post(() -> {
                    if (isAdded()) uploadMediaAndSend(userMessage, mediaUri, mediaSize, type);
                });
            }

            @Override
            public void onLimitReached(String message) {
                typewriterHandler.post(() -> {
                    if (isAdded()) handleQuotaError(message);
                });
            }
        });
    }

    // --- UPLOAD GÜVENLİK AYARI ---
    private void uploadMediaAndSend(MessageModel userMessage, byte[] mediaBytes, String type) {
        FirebaseDBManager.StorageCallback callback = new FirebaseDBManager.StorageCallback() {
            @Override
            public void onSuccess(String mediaUrl) {
                // Upload biter bitmez kullanıcı çıksa bile modele URL'i yaz
                if (type.equals("IMAGE"))
                    userMessage.setImageUrl(mediaUrl);
                else if (type.equals("AUDIO"))
                    userMessage.setAudioUrl(mediaUrl);
                else if (type.equals("DOC"))
                    userMessage.setDocUrl(mediaUrl);

                // Ve Gemini'yi tetikle (Burada isAdded() kontrolü YOK, arka planda da
                // çalışmalı)
                sendMessageToGeminiInternal(userMessage, mediaBytes.length, type, true, null);
            }

            @Override
            public void onError(String error) {
                typewriterHandler.post(() -> {
                    if (isAdded()) {
                        handleQuotaError(error);
                    }
                });
            }
        };

        if (type.equals("IMAGE")) {
            dbManager.uploadImageToStorage(mediaBytes, callback);
        } else {
            String ext = "bin";
            if (userMessage.getFileName() != null && userMessage.getFileName().contains(".")) {
                ext = userMessage.getFileName().substring(userMessage.getFileName().lastIndexOf(".") + 1);
            } else if (type.equals("DOC"))
                ext = "pdf";
            else if (type.equals("AUDIO"))
                ext = "mp3";

            dbManager.uploadByteFileToStorage(mediaBytes, ext, callback);
        }
    }

    private void uploadMediaAndSend(MessageModel userMessage, Uri mediaUri, long mediaSize, String type) {
        String extension = extensionFor(userMessage.getFileName(), type);
        dbManager.uploadUriFileToStorage(mediaUri, extension, new FirebaseDBManager.StorageCallback() {
            @Override
            public void onSuccess(String mediaUrl) {
                if ("AUDIO".equals(type)) userMessage.setAudioUrl(mediaUrl);
                else if ("DOC".equals(type)) userMessage.setDocUrl(mediaUrl);
                sendMessageToGeminiInternal(userMessage, Math.max(0L, mediaSize), type, true, null);
            }

            @Override
            public void onError(String error) {
                typewriterHandler.post(() -> {
                    if (isAdded()) handleQuotaError(error);
                });
            }
        });
    }

    private String extensionFor(String fileName, String type) {
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(fileName.lastIndexOf('.') + 1);
        }
        return "DOC".equals(type) ? "pdf" : "mp3";
    }

    private void sendMessageToGeminiInternal(MessageModel userMessage, long mediaSize, String mediaType,
            boolean consumeQuota, @Nullable String manualPrompt) {

        if (isAdded() && binding != null) {
            if (adapter.getItemCount() > 0 && !adapter.getLastItem().getId().equals("loading")) {
                MessageModel loadingMsg = new MessageModel("loading", "model", "...", "TEXT", Timestamp.now());
                adapter.addMessage(loadingMsg);
                scrollToBottom();
            }
        }

        final String chatIdSnapshot = currentChatId;

        dbManager.saveMessage(chatIdSnapshot, userMessage);
        dbManager.updateChatPreview(chatIdSnapshot, userMessage.getContent(), userMessage.getType(), Timestamp.now());

        String contentToSend = (manualPrompt != null) ? manualPrompt : userMessage.getContent();

        String mediaUrl = null;
        if (mediaType != null) {
            if ("IMAGE".equals(mediaType))
                mediaUrl = userMessage.getImageUrl();
            else if ("AUDIO".equals(mediaType))
                mediaUrl = userMessage.getAudioUrl();
            else if ("DOC".equals(mediaType))
                mediaUrl = userMessage.getDocUrl();
        }

        sessionManager.setAILoading(true);

        geminiClient.sendMessage(contentToSend, mediaUrl, mediaType, new GeminiClient.GeminiCallback() {
            @Override
            public void onSuccess(String response) {
                // Her halükarda veriyi mutlaka kaydet (Arka planda da çalışmalı)
                MessageModel botMessage = new MessageModel(String.valueOf(System.currentTimeMillis()), "model", response, "TEXT", Timestamp.now());
                dbManager.saveMessage(chatIdSnapshot, botMessage);
                dbManager.updateChatPreview(chatIdSnapshot, response, "TEXT", Timestamp.now());

                if (consumeQuota) {
                    long size = mediaSize > 0L ? mediaSize : contentToSend.length();
                    dbManager.incrementUsage(mediaType, size);
                }

                // Sadece aktif sohbet bu istek yapıldığındaki sohbet ise UI ve Session'ı güncelle
                if (chatIdSnapshot != null && chatIdSnapshot.equals(sessionManager.getActiveChatId())) {
                    sessionManager.setAILoading(false);
                    sessionManager.addMessage(botMessage);

                    // Ön yüz: UI Güncellemesi
                    SessionManager.ChatCallback cb = sessionManager.getChatCallback();
                    if (cb != null) {
                        cb.onSuccess(botMessage);
                    }
                }
            }

            @Override
            public void onError(Throwable t) {
                if (chatIdSnapshot != null && chatIdSnapshot.equals(sessionManager.getActiveChatId())) {
                    sessionManager.setAILoading(false);

                    SessionManager.ChatCallback cb = sessionManager.getChatCallback();
                    if (cb != null) {
                        cb.onError(t);
                    }
                }
            }
        });
    }

    private void handleQuotaError(String message) {
        sessionManager.setAILoading(false);
        if (!isAdded())
            return;

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
        if (!isAdded())
            return;
        Toast.makeText(getContext(), R.string.new_chat, Toast.LENGTH_SHORT).show();
        adapter.clearMessages();
        sessionManager.clearSession();
        geminiClient.resetChat();
        resetMediaSelections();
        currentChatId = "chat_" + System.currentTimeMillis();
        sessionManager.setActiveChatId(currentChatId);
        setLoadingState(false);
        updateEmptyState();
        if (getArguments() != null)
            getArguments().remove("chatId");
    }

    private void restoreSessionToUI() {
        List<MessageModel> msgs = sessionManager.getActiveMessages();
        if (msgs != null && !msgs.isEmpty()) {
            adapter.setMessages(msgs);
        }
        if (sessionManager.isAILoading()) {
            setLoadingState(true);
            if (adapter.getItemCount() == 0 || !"loading".equals(adapter.getLastItem().getId())) {
                MessageModel loadingMsg = new MessageModel("loading", "model", "...", "TEXT", Timestamp.now());
                adapter.addMessage(loadingMsg);
            }
        }
        scrollToBottom();
        updateEmptyState();
    }

    private void loadOldMessages(String chatId) {
        setLoadingState(true);
        adapter.clearMessages();
        dbManager.getMessages(chatId, new FirebaseDBManager.MessagesCallback() {
            @Override
            public void onSuccess(List<MessageModel> messages) {
                if (!isAdded())
                    return;
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
                if (!isAdded())
                    return;
                setLoadingState(false);
            }
        });
    }

    private void resetMediaSelections() {
        selectedImageBytes = null;
        selectedMediaUri = null;
        selectedMediaType = null;
        selectedMediaSize = -1L;
        selectedFileName = null;
        extractedDocText = null;
        extractedDataText = null;
        isLinkRequest = false;
        if (binding != null) {
            binding.etMessage.setHint(getString(R.string.hint_type_message));
        }
    }

    private void toggleAttachmentMenu() {
        if (binding == null)
            return;
        if (binding.layoutAttachments.getVisibility() == View.VISIBLE)
            hideAttachmentMenu();
        else
            showAttachmentMenu();
    }

    private void showAttachmentMenu() {
        if (binding == null)
            return;
        hideKeyboard();
        binding.layoutAttachments.setVisibility(View.VISIBLE);
    }

    private void hideAttachmentMenu() {
        if (binding == null || binding.layoutAttachments.getVisibility() != View.VISIBLE)
            return;

        binding.layoutAttachments.setVisibility(View.GONE);

        binding.btnAttach.setRotation(0f);
    }

    private void hideKeyboard() {
        if (getActivity() == null)
            return;
        View view = getActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void setLoadingState(boolean loading) {
        if (binding == null)
            return;
        this.isLoading = loading;
        binding.btnSend.setEnabled(!loading);
        binding.btnAttach.setEnabled(!loading);
        binding.btnAttach.setAlpha(loading ? 0.5f : 1.0f);
        binding.btnSend.setAlpha(loading ? 0.5f : 1.0f);
    }

    private void scrollToBottom() {
        isUserScrollingUp = false;
        if (binding != null && adapter.getItemCount() > 0)
            binding.chatRecyclerView.scrollToPosition(adapter.getItemCount() - 1);
    }

    private void scrollToBottomIfAtBottom() {
        if (binding == null || adapter.getItemCount() == 0 || isUserScrollingUp) return;
        LinearLayoutManager lm = (LinearLayoutManager) binding.chatRecyclerView.getLayoutManager();
        if (lm != null) {
            int lastVisible = lm.findLastVisibleItemPosition();
            if (lastVisible >= adapter.getItemCount() - 2) {
                binding.chatRecyclerView.scrollToPosition(adapter.getItemCount() - 1);
            }
        }
    }

    private void updateEmptyState() {
        if (binding == null)
            return;
        if (adapter.getItemCount() == 0) {
            binding.layoutEmptyState.setVisibility(View.VISIBLE);
            binding.chatRecyclerView.setVisibility(View.GONE);
        } else {
            binding.layoutEmptyState.setVisibility(View.GONE);
            binding.chatRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    private void startTypewriterEffect(MessageModel botMessage, String fullText) {
        if (!isAdded() || binding == null) return;

        final String[] tokens = fullText.split("(?<=\\s)|(?=\\s)");
        final int tokenCount = tokens.length;
        if (tokenCount == 0) {
            botMessage.setContent(fullText);
            adapter.addMessage(botMessage);
            setLoadingState(false);
            return;
        }

        // Set active typewriter state for fragment lifecycle protection/restoration
        activeTypewriterMessage = botMessage;
        activeTypewriterFullText = fullText;

        // Dynamic tokens per tick targeting ~3.0s total time for an ultra-premium, readable flow
        final int tickDelay = 45; // ms
        final int tokensPerTick = Math.max(1, tokenCount / 70);

        int initialLimit = Math.min(tokensPerTick, tokenCount);
        StringBuilder initialText = new StringBuilder();
        for (int i = 0; i < initialLimit; i++) {
            initialText.append(tokens[i]);
        }

        botMessage.setContent(initialText.toString());
        adapter.addMessage(botMessage);
        scrollToBottom();
        setLoadingState(false);

        if (activeTypewriterRunnable != null) {
            typewriterHandler.removeCallbacks(activeTypewriterRunnable);
        }

        activeTypewriterRunnable = new Runnable() {
            private int currentTokenIndex = initialLimit;
            private final StringBuilder currentText = new StringBuilder(initialText.toString());

            @Override
            public void run() {
                if (!isAdded() || binding == null) {
                    activeTypewriterRunnable = null;
                    return;
                }

                if (currentTokenIndex < tokenCount) {
                    int limit = Math.min(currentTokenIndex + tokensPerTick, tokenCount);
                    for (int i = currentTokenIndex; i < limit; i++) {
                        currentText.append(tokens[i]);
                    }
                    currentTokenIndex = limit;

                    botMessage.setContent(currentText.toString());

                    int pos = adapter.getItemCount() - 1;
                    if (pos >= 0) {
                        adapter.notifyItemChanged(pos, "typing");
                    }
                    scrollToBottomIfAtBottom();

                    typewriterHandler.postDelayed(this, tickDelay);
                } else {
                    botMessage.setContent(fullText);
                    int pos = adapter.getItemCount() - 1;
                    if (pos >= 0) {
                        adapter.notifyItemChanged(pos);
                    }
                    activeTypewriterRunnable = null;
                    activeTypewriterMessage = null;
                    activeTypewriterFullText = null;
                }
            }
        };

        typewriterHandler.postDelayed(activeTypewriterRunnable, tickDelay);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (typewriterHandler != null && activeTypewriterRunnable != null) {
            typewriterHandler.removeCallbacks(activeTypewriterRunnable);
            activeTypewriterRunnable = null;
        }
        if (activeTypewriterMessage != null && activeTypewriterFullText != null) {
            activeTypewriterMessage.setContent(activeTypewriterFullText);
            activeTypewriterMessage = null;
            activeTypewriterFullText = null;
        }
        if (sessionManager != null) {
            sessionManager.setChatCallback(null);
        }
        if (mediaExecutor != null) {
            mediaExecutor.shutdownNow();
            mediaExecutor = null;
        }
        binding = null;
    }
}
