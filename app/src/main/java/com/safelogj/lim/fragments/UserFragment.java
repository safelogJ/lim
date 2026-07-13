package com.safelogj.lim.fragments;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.textfield.TextInputLayout;
import com.safelogj.lim.AppController;
import com.safelogj.lim.R;
import com.safelogj.lim.databinding.FragmentUserBinding;
import com.safelogj.lim.viewmodels.AuthViewModel;

import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A simple {@link Fragment} subclass.
 * Use the  factory method to
 * create an instance of this fragment.
 */
public class UserFragment extends Fragment {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9]{3,20}$");
    private static final String REMOVE = "remove";
    private final ActivityResultCallback<ActivityResult> callbackForGeneralPermitURI = result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            if (uri != null) {
                DocumentFile documentFile = DocumentFile.fromSingleUri(requireContext(), uri);
                if (documentFile.exists()) {
                    try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                        CertificateFactory cf = CertificateFactory.getInstance("X.509");
                        X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
                        byte[] certBytes = cert.getEncoded();
                        String cName = documentFile.getName();
                        saveCert(cName, certBytes);
                        Log.d(AppController.LOG_TAG, "Сертификат успешно импортирован");
                    } catch (Exception e) {
                        drawCertName(getString(R.string.cert_import_error));
                        Log.i(AppController.LOG_TAG, "Ошибка импорта сертификата: " + e.getMessage());
                    }
                }
            }
        }
    };
    private final ActivityResultLauncher<Intent> requestGeneralPermitURI =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), callbackForGeneralPermitURI);

    private final ActivityResultCallback<Boolean> callbackAskReadFilePermit = result -> {
        if (Boolean.TRUE == result) {
            requestGeneralPermitURI.launch(getIntentActionOpenDoc());
        }
    };
    private final ActivityResultLauncher<String> requestAskReadFilePermit =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), callbackAskReadFilePermit);

    private FragmentUserBinding mBinding;
    private AppController controller;
    private AuthViewModel authViewModel;
    private String username;
    private String password;
    private String address;
    private String displayName;
    private String certName;

    public UserFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = (AppController) requireActivity().getApplication();
        getUserValues();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mBinding = FragmentUserBinding.inflate(inflater, container, false);
        return mBinding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        authViewModel = new ViewModelProvider(this).get(AuthViewModel.class);
        authViewModel.getResultMessage().observe(getViewLifecycleOwner(), msg -> {
            if (msg != null) {
                mBinding.resultTextView.setText(msg);
            }
        });

        mBinding.loginEditText.setText(username);
        mBinding.passwordEditText.setText(password);
        mBinding.addressEditText.setText(address);
        mBinding.displayNameEditText.setText(displayName);
        drawCertName(certName);
        setRegBtnListener();
        addCertBtnListener();
        setTouchFieldListeners();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mBinding = null;
    }

    @Nullable
    private String getUserFromField() {
        Editable name = mBinding.loginEditText.getText();
        if (name != null && isUsernameValid(name.toString().trim())) {
            return name.toString().trim();
        }
        return null;
    }

    @Nullable
    private String getPassFromField() {
        Editable pass = mBinding.passwordEditText.getText();
        if (pass != null && isPasswordValid(pass.toString().trim())) {
            return pass.toString().trim();
        }
        return null;
    }

    @Nullable
    private String getAddressFromField() {
        Editable ip = mBinding.addressEditText.getText();
        if (ip != null && isLanAddress(ip.toString().trim())) {
            return ip.toString().trim();
        }
        return null;
    }

    @Nullable
    private String getDisplayNameFromField() {
        Editable dName = mBinding.displayNameEditText.getText();
        if (dName != null && isDisplayNameValid(dName.toString().trim())) {
            return dName.toString().trim();
        }
        return null;
    }

    private boolean isLanAddress(@Nullable String address) {
        if (address == null) return false;

        try {
            String[] ip = address.split("\\.");
            if (ip.length != 4) return false;

            int a = Integer.parseInt(ip[0]);
            int b = Integer.parseInt(ip[1]);
            int c = Integer.parseInt(ip[2]);
            int d = Integer.parseInt(ip[3]);

            if (isInvalidV4(a) || isInvalidV4(b) || isInvalidV4(c) || isInvalidV4(d))
                return false;

            return (a == 192 && b == 168) || (a == 172 && b >= 16 && b <= 31) || a == 10;

        } catch (Exception e) {
            return false;
        }
    }

    private boolean isInvalidV4(int digit) {
        return digit > 255 || digit < 0;
    }

    private boolean isUsernameValid(@NonNull String username) {
        return (username.length() >= 3 && username.length() <= 20) && USERNAME_PATTERN.matcher(username).matches();
    }

    private boolean isPasswordValid(@NonNull String password) {
        return password.length() >= 6 && password.length() <= 20;
    }

    @NonNull
    private String sanitizeDisplayName(@NonNull String displayName) {
        String clean = displayName.replaceAll("\\p{Cc}", AppController.EMPTY_STRING);
        clean = clean.replaceAll("\\s+", " ");
        return clean.trim();
    }

    private boolean isDisplayNameValid(@NonNull String displayName) {
        String cleanName = sanitizeDisplayName(displayName);
        return cleanName.length() >= 3 && cleanName.length() <= 20;
    }

    private void setRegBtnListener() {
        mBinding.regBtn.setOnClickListener(v -> {
            String user = getUserFromField();
            String pass = getPassFromField();
            String ip = getAddressFromField();
            String dName = getDisplayNameFromField();

            if (ip == null) {
                mBinding.addressInputLayout.setError(getString(R.string.ip_error));
                return;
            }
            if (user == null) {
                mBinding.loginInputLayout.setError(getString(R.string.login_error));
                return;
            }
            if (pass == null) {
                mBinding.passwordInputLayout.setError(getString(R.string.pass_error));
                return;
            }
            if (dName == null) {
                mBinding.displayNameInputLayout.setError(getString(R.string.display_name_error));
                return;
            }
            getUserValues();
            authViewModel.setResultMessage(AppController.EMPTY_STRING);
            mBinding.displayNameEditText.setText(dName);
            sendCommand(ip, user, pass, dName);
            controller.setServerIp(ip);
            controller.setServerUrl(ip);
            controller.writeSettingsToFile();
        });
    }

    private void sendCommand(String ip, String user, String pass, String dName) {
        if (controller.getUserId() > 0 && username.equals(user) && REMOVE.equals(dName.toLowerCase(Locale.US))) {  // remove
            authViewModel.deleteAccount(username, password);
            Log.d(AppController.LOG_TAG, "Удаление аккаунта");
        } else if (controller.getUserId() > 0 && controller.getServerIp().equals(ip) && username.equals(user)
                && (!password.equals(pass) || !displayName.equals(dName))) { // edit
            authViewModel.editUser(username, password, displayName.equals(dName) ? null : dName, password.equals(pass) ? null : pass);
            Log.d(AppController.LOG_TAG, "Редактирование аккаунта");
        } else if (controller.getUserId() == 0 && !REMOVE.equals(dName.toLowerCase(Locale.US))) {  // reg
            authViewModel.register(user, pass, dName);
            Log.d(AppController.LOG_TAG, "Регистрация аккаунта");
        } else if (REMOVE.equals(dName.toLowerCase(Locale.US))) {
            mBinding.resultTextView.setText(getString(R.string.display_name_reserved));
        } else if (!controller.getServerIp().equals(ip) || !username.equals(user)) {
            mBinding.resultTextView.setText(getString(R.string.reinstall));
        }
    }

    private void addCertBtnListener() {
        mBinding.addCertButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2
                    && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestAskReadFilePermit.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
                return;
            }
            requestGeneralPermitURI.launch(getIntentActionOpenDoc());
        });
    }

    private void setTouchFieldListeners() {
        mBinding.addressEditText.addTextChangedListener(getTextWatcher(mBinding.addressInputLayout));
        mBinding.loginEditText.addTextChangedListener(getTextWatcher(mBinding.loginInputLayout));
        mBinding.passwordEditText.addTextChangedListener(getTextWatcher(mBinding.passwordInputLayout));
        mBinding.displayNameEditText.addTextChangedListener(getTextWatcher(mBinding.displayNameInputLayout));
    }

    private TextWatcher getTextWatcher(TextInputLayout inputLayout) {
        return new TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Как только юзер ввел хотя бы один символ — убираем красную рамку
                inputLayout.setError(null);
            }

            // Остальные два метода (beforeTextChanged и afterTextChanged) оставляем пустыми
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                //
            }

            @Override
            public void afterTextChanged(Editable s) {
                //
            }
        };
    }


    private Intent getIntentActionOpenDoc() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/x-x509-ca-cert", "application/pkix-cert", "application/x-pem-file"});
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        return intent;
    }

    private void saveCert(String name, byte[] certBytes) {
        controller.setCertName(name);
        controller.setCertBytes(certBytes);
        controller.writeSettingsToFile();
        drawCertName(name);
    }

    private void drawCertName(String name) {
        mBinding.certTextView.setText(name);
    }

    private void getUserValues() {
        username = controller.getUsername();
        password = controller.getPassword();
        address = controller.getServerIp();
        displayName = controller.getDisplayName();
        certName = controller.getCertName();
    }

}

