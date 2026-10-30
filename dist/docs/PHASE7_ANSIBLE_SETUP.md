# Phase 7 — Ansible setup and secure demo guide

Giai đoạn 7 giữ Java là ứng dụng chính. Java tạo file UTF-8, tạo inventory tạm từ allowlist, rồi gọi đúng hai playbook cố định bằng `ProcessBuilder`. Java File Transfer qua socket và Ansible configuration management là hai cơ chế độc lập.

## 1. Control Node

Khuyến nghị production: một Linux Control Node được quản trị riêng. Có thể dùng WSL2 để demo trên Windows, nhưng tài liệu Ansible nêu WSL không phải môi trường Control Node được hỗ trợ cho production.

Ví dụ cài bằng `pipx` trên Linux/WSL2:

```bash
python3 -m pip install --user pipx
python3 -m pipx ensurepath
pipx install --include-deps ansible
ansible-galaxy collection install -r ansible/collections/requirements.yml

# Chọn đúng transport Windows được sử dụng:
pipx inject ansible "pypsrp<=1.0.0"   # PSRP
# hoặc
pipx inject ansible "pywinrm>=0.4.0" # WinRM

ansible --version
ansible-playbook --version
```

Không cài Ansible trên managed Client. Client Linux cần SSH; Client Windows cần PSRP/WinRM hoặc OpenSSH đã được quản trị viên chuẩn bị.

Tài liệu chính thức:

- https://docs.ansible.com/projects/ansible/latest/intro_installation.html
- https://docs.ansible.com/projects/ansible/latest/os_guide/intro_windows.html
- https://docs.ansible.com/projects/ansible/latest/os_guide/windows_winrm.html
- https://docs.ansible.com/projects/ansible-core/stable-2.21/os_guide/windows_ssh.html

## 2. Linux managed host qua SSH

1. Quản trị viên tạo tài khoản automation có quyền tối thiểu đối với đúng thư mục đích.
2. Tạo sẵn thư mục đích, ví dụ `/opt/lanmonitoring`, với owner/mode phù hợp. Playbook chỉ quản lý file đã cấu hình, không tự cấp quyền hệ điều hành.
3. Dùng SSH key hoặc ssh-agent; thêm host key thật vào `known_hosts`. `ansible.cfg` giữ `host_key_checking=True`.
4. Không tắt host-key checking và không đưa private key vào repository.

Kiểm tra thủ công từ Control Node:

```bash
ssh automation@linux-host
ansible linux_lab_01 -i generated-inventory.yml -m ansible.builtin.ping \
  --extra-vars @vault-hosts.yml --vault-password-file ~/.config/lanmonitoring/vault-password
```

## 3. Windows managed host

Ưu tiên PSRP/WinRM qua HTTPS với certificate hợp lệ. Server Java không tự bật WinRM/SSH, không sửa firewall và không chạy bootstrap từ xa.

Quy trình quản trị viên:

1. Chọn PSRP, WinRM hoặc OpenSSH; cấu hình transport trực tiếp trên máy Windows theo chính sách tổ chức.
2. Với PSRP/WinRM, tạo HTTPS listener bằng certificate có SAN phù hợp, giới hạn firewall theo Control Node và giữ certificate validation ở `validate`.
3. Không đặt `AllowUnencrypted=true`, không dùng tùy chọn bỏ qua CA/CN/revocation để làm demo PASS.
4. Tài khoản automation chỉ có quyền cần thiết trên thư mục đích, ví dụ `C:\ProgramData\LANMonitoringServer`.
5. Với Windows SSH, dùng Ansible Core 2.18+ và OpenSSH được Windows hỗ trợ; inventory generator đặt `ansible_shell_type=powershell`.

Kiểm tra transport trước khi chạy Java:

```bash
ansible windows_lab_01 -i generated-inventory.yml -m ansible.windows.win_ping \
  --extra-vars @vault-hosts.yml --vault-password-file ~/.config/lanmonitoring/vault-password
```

## 4. Allowlist managed hosts

Sao chép `ansible/config/managed-hosts.example.json` ra ngoài source tree, ví dụ:

```text
~/.lanmonitoringserver/ansible-managed-hosts-v1.json
```

Mỗi entry có alias inventory, platform, address, port, connection và đường dẫn đích. `applicationClientId` chỉ là liên kết hiển thị tùy chọn; Java Client đang Online không tự động được đưa vào inventory. Chỉ entry `enabled=true` mới được chọn để chạy.

Đường dẫn đích bắt buộc kết thúc bằng `lan_monitoring_sync.txt`. Linux dùng absolute POSIX path; Windows dùng absolute drive path. Alias/address/path đều được kiểm tra trước khi sinh YAML.

## 5. Credential và Ansible Vault

Tạo file ở ngoài repository:

```yaml
vault_hosts:
  linux_lab_01:
    ansible_user: REPLACE_WITH_AUTOMATION_USER
    ansible_ssh_private_key_file: /home/automation/.ssh/id_ed25519
  windows_lab_01:
    ansible_user: REPLACE_WITH_MANAGED_ACCOUNT
    ansible_password: REPLACE_INTERACTIVELY_BEFORE_ENCRYPTING
    ansible_psrp_auth: negotiate
```

Mã hóa ngay và giới hạn quyền filesystem:

```bash
mkdir -p ~/.config/lanmonitoring
chmod 700 ~/.config/lanmonitoring
printf '%s\n' 'REPLACE_WITH_STRONG_VAULT_PASSWORD' > ~/.config/lanmonitoring/vault-password
chmod 600 ~/.config/lanmonitoring/vault-password
ansible-vault encrypt ~/.config/lanmonitoring/vault-hosts.yml \
  --vault-password-file ~/.config/lanmonitoring/vault-password
chmod 600 ~/.config/lanmonitoring/vault-hosts.yml
```

Java từ chối credentials file không có header `$ANSIBLE_VAULT;`. Password file và vault file chỉ được truyền thành argument riêng; nội dung secret không được Java đọc hoặc ghi log. Ansible Vault bảo vệ dữ liệu at-rest; vẫn cần TLS/SSH và quyền filesystem.

## 6. Chạy Java với native Linux Control Node

```bash
java \
  -Dlanmonitoring.ansible.mode=native \
  -Dlanmonitoring.ansible.executable=ansible-playbook \
  -Dlanmonitoring.ansible.project=/opt/LANMonitoringServer/ansible \
  -Dlanmonitoring.ansible.workspace=/var/lib/lanmonitoring/ansible \
  -Dlanmonitoring.ansible.hosts=$HOME/.lanmonitoringserver/ansible-managed-hosts-v1.json \
  -Dlanmonitoring.ansible.vault.file=$HOME/.config/lanmonitoring/vault-hosts.yml \
  -Dlanmonitoring.ansible.vault.password.file=$HOME/.config/lanmonitoring/vault-password \
  -Dlanmonitoring.server.role=ADMIN \
  -jar dist/LANMonitoringServer.jar --server
```

## 7. Chạy Java trên Windows và điều phối WSL2

Workspace/project/vault phải nằm trên drive được WSL automount dưới `/mnt/<drive>`. UNC `\\wsl$` không được runner chấp nhận.

```powershell
java `
  "-Dlanmonitoring.ansible.mode=wsl" `
  "-Dlanmonitoring.ansible.wsl.distribution=Ubuntu" `
  "-Dlanmonitoring.ansible.project=D:\LT_MMT\LANMonitoringServer\ansible" `
  "-Dlanmonitoring.ansible.workspace=D:\LANMonitoringRuntime\ansible" `
  "-Dlanmonitoring.ansible.hosts=C:\SecureConfig\managed-hosts.json" `
  "-Dlanmonitoring.ansible.vault.file=C:\SecureConfig\vault-hosts.yml" `
  "-Dlanmonitoring.ansible.vault.password.file=C:\SecureConfig\vault-password" `
  "-Dlanmonitoring.server.role=ADMIN" `
  -jar dist\LANMonitoringServer.jar --server
```

Runner chuyển drive path sang `/mnt/<drive>/...` và gọi `wsl.exe -- ansible-playbook` bằng danh sách argument; không ghép shell command.

## 8. Quy trình UI/demo

1. Mở tab **Ansible Management** và **Reload Managed Hosts**.
2. Nhập nội dung, nhấn **Generate File** và ghi lại SHA-256.
3. Chọn đúng host allowlist, nhấn **Deploy / Sync to Clients**.
4. Xem log và kết quả độc lập `SUCCESS/FAILED/UNREACHABLE/TIMED_OUT/CANCELLED`.
5. Nhấn **Verify Synchronization** để chạy riêng các task SHA-256.
6. Chạy Deploy lần hai với nội dung không đổi: kết quả phải `changed=false`.
7. Sửa nội dung, Generate và Deploy lại: checksum mới và `changed=true`.

Không có đồng bộ nền tự động. Không có playbook/command do Client gửi. Chỉ hai playbook bundled được runner lựa chọn.

## 9. Syntax/build gate

Trên Control Node có Ansible:

```bash
ansible-playbook --syntax-check ansible/playbooks/sync_linux.yml
ansible-playbook --syntax-check ansible/playbooks/sync_windows.yml
ant release-phase7
```

Máy phát triển hiện tại không có `ansible-playbook` và WSL service bị Access Denied, nên chưa chạy được hai lệnh syntax-check của chính Ansible hoặc deploy host thật. `AnsibleIntegrationTest` chỉ xác minh cấu trúc YAML/module bắt buộc và ProcessBuilder/parser bằng tiến trình giả lập kiểm soát.
