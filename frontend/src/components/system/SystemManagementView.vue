<template>
  <section class="system-page">
    <el-tabs v-model="activeTab" class="system-tabs">
      <!-- 用户管理 -->
      <el-tab-pane label="用户管理" name="users">
        <div class="tab-toolbar">
          <el-input
            v-model="userSearch"
            size="small"
            clearable
            placeholder="按账号搜索"
            class="search-input"
            @keyup.enter="loadUsers"
            @clear="loadUsers"
          />
          <el-button size="small" @click="loadUsers">查询</el-button>
          <el-button size="small" @click="loadUsers()">刷新</el-button>
        </div>
        <el-alert
          v-if="usersError"
          type="error"
          show-icon
          :closable="false"
          class="gap-block"
          :title="'用户列表加载失败：' + usersError"
          description="用户管理接口（R10）尚未接入或无权限，请确认后端服务与账号角色后再试。"
        />
        <el-table v-loading="usersLoading" :data="users" height="100%" empty-text="暂无用户">
          <el-table-column prop="username" label="账号" min-width="140" show-overflow-tooltip />
          <el-table-column prop="displayName" label="显示名" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.displayName || '-' }}</template>
          </el-table-column>
          <el-table-column label="角色" min-width="160">
            <template #default="{ row }">
              <el-tag v-for="role in row.roles || []" :key="role" size="small" class="role-tag">{{
                role
              }}</el-tag>
              <span v-if="!row.roles?.length">-</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'danger'">{{
                row.status === 'ENABLED' ? '已启用' : '已禁用'
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEditUser(row)"
                >编辑角色</el-button
              >
              <el-button
                v-if="row.status !== 'DISABLED'"
                size="small"
                link
                type="danger"
                @click="toggleUser(row, false)"
                >禁用</el-button
              >
              <el-button v-else size="small" link type="success" @click="toggleUser(row, true)"
                >启用</el-button
              >
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="userPage"
          :page-size="userPageSize"
          :total="userTotal"
          layout="total, prev, pager, next"
          class="tab-pagination"
          @current-change="loadUsers"
        />
      </el-tab-pane>

      <!-- 角色管理 -->
      <el-tab-pane label="角色管理" name="roles">
        <div class="tab-toolbar">
          <span class="toolbar-title">角色与功能权限、资源授权</span>
          <el-button size="small" @click="loadRoles">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreateRole">新建角色</el-button>
        </div>
        <el-alert
          v-if="rolesError"
          type="error"
          show-icon
          :closable="false"
          class="gap-block"
          :title="'角色列表加载失败：' + rolesError"
          description="角色管理接口（R10）尚未接入或无权限，请确认后端服务与账号角色后再试。"
        />
        <el-table v-loading="rolesLoading" :data="roles" height="100%" empty-text="暂无角色">
          <el-table-column prop="name" label="角色名称" min-width="140" show-overflow-tooltip />
          <el-table-column prop="code" label="角色编码" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.code || '-' }}</template>
          </el-table-column>
          <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '-' }}</template>
          </el-table-column>
          <el-table-column label="权限数" width="90" align="center">
            <template #default="{ row }">{{ row.permissions?.length ?? 0 }}</template>
          </el-table-column>
          <el-table-column label="内置" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.builtin" size="small" type="info">内置</el-tag>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEditRole(row)"
                >编辑</el-button
              >
              <el-button
                size="small"
                link
                type="danger"
                :disabled="row.builtin"
                @click="removeRole(row)"
                >删除</el-button
              >
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑用户角色 -->
    <el-dialog v-model="editUserVisible" title="编辑用户角色" width="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="'账号：' + editingUser?.username">
          <el-input v-model="editUserDisplayName" placeholder="显示名（可选）" maxlength="64" />
        </el-form-item>
        <el-form-item label="角色（后端强制校验）" required>
          <el-select
            v-model="editUserRoles"
            multiple
            filterable
            class="full-width"
            placeholder="选择角色"
          >
            <el-option
              v-for="role in roles"
              :key="role.id"
              :label="role.name"
              :value="role.code || role.name"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editUserVisible = false">取消</el-button>
        <el-button type="primary" :loading="editUserSaving" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑角色 -->
    <el-dialog
      v-model="editRoleVisible"
      :title="editingRole ? '编辑角色' : '新建角色'"
      width="720px"
      destroy-on-close
    >
      <el-form label-position="top">
        <div class="form-row">
          <el-form-item label="角色名称" required class="grow">
            <el-input v-model="roleForm.name" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item label="角色编码" :required="!editingRole" class="grow">
            <el-input
              v-model="roleForm.code"
              maxlength="64"
              :disabled="Boolean(editingRole)"
              placeholder="例如 agent_developer"
            />
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="roleForm.description" type="textarea" :rows="2" maxlength="256" />
        </el-form-item>
        <el-form-item label="功能权限">
          <el-tree
            ref="permissionTreeRef"
            :data="permissionTree"
            node-key="id"
            show-checkbox
            default-expand-all
            :props="{ label: 'label', children: 'children' }"
            class="permission-tree"
          />
        </el-form-item>
        <el-collapse class="resource-collapse">
          <el-collapse-item title="资源级授权（可选）" name="resources">
            <el-form-item label="智能体">
              <el-select
                v-model="roleResources.agents"
                multiple
                filterable
                class="full-width"
                placeholder="授权可见的智能体"
              >
                <el-option
                  v-for="item in resourceOptions.agents"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="知识库">
              <el-select
                v-model="roleResources.knowledgeBases"
                multiple
                filterable
                class="full-width"
                placeholder="授权可见的知识库"
              >
                <el-option
                  v-for="item in resourceOptions.knowledgeBases"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="工具">
              <el-select
                v-model="roleResources.tools"
                multiple
                filterable
                class="full-width"
                placeholder="授权可见的工具"
              >
                <el-option
                  v-for="item in resourceOptions.tools"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="工作流">
              <el-select
                v-model="roleResources.workflows"
                multiple
                filterable
                class="full-width"
                placeholder="授权可见的工作流"
              >
                <el-option
                  v-for="item in resourceOptions.workflows"
                  :key="item.id"
                  :label="item.name"
                  :value="item.id"
                />
              </el-select>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="editRoleVisible = false">取消</el-button>
        <el-button type="primary" :loading="editRoleSaving" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { TreeInstance } from 'element-plus';
import {
  createPlatformRole,
  deletePlatformRole,
  listPlatformAssets,
  listPlatformRoles,
  listPlatformUsers,
  updatePlatformRole,
  updatePlatformUser,
} from '../../api/platform';
import type {
  PlatformAsset,
  PlatformAssetType,
  PlatformRole,
  PlatformUser,
} from '../../types/platform';
import { authContext, isAdmin } from '../../composables/useAuthState';

const activeTab = ref<'users' | 'roles'>('users');

// 用户状态
const users = ref<PlatformUser[]>([]);
const usersLoading = ref(false);
const usersError = ref('');
const userSearch = ref('');
const userPage = ref(1);
const userPageSize = ref(20);
const userTotal = ref(0);

// 角色状态
const roles = ref<PlatformRole[]>([]);
const rolesLoading = ref(false);
const rolesError = ref('');

// 用户编辑
const editUserVisible = ref(false);
const editingUser = ref<PlatformUser | null>(null);
const editUserRoles = ref<string[]>([]);
const editUserDisplayName = ref('');
const editUserSaving = ref(false);

// 角色编辑
const editRoleVisible = ref(false);
const editingRole = ref<PlatformRole | null>(null);
const editRoleSaving = ref(false);
const roleForm = ref({ name: '', code: '', description: '' });
const roleResources = ref({
  agents: [] as number[],
  knowledgeBases: [] as number[],
  tools: [] as number[],
  workflows: [] as number[],
});
const resourceOptions = ref<Record<string, PlatformAsset[]>>({
  agents: [],
  knowledgeBases: [],
  tools: [],
  workflows: [],
});
const permissionTreeRef = ref<TreeInstance | null>(null);

// 功能权限目录（前端展示；实际权限由后端强制校验）
const permissionTree = [
  {
    id: 'chat',
    label: '对话工作台',
    children: [
      { id: 'chat:use', label: '发起会话' },
      { id: 'chat:agent-debug', label: '智能体调试' },
    ],
  },
  {
    id: 'agent',
    label: '智能体',
    children: [
      { id: 'agent:read', label: '查看智能体' },
      { id: 'agent:write', label: '创建/修改草稿' },
      { id: 'agent:publish', label: '发布/下线' },
    ],
  },
  {
    id: 'workflow',
    label: '工作流',
    children: [
      { id: 'workflow:read', label: '查看工作流' },
      { id: 'workflow:write', label: '编排/修改' },
      { id: 'workflow:run', label: '试运行/上下线' },
    ],
  },
  {
    id: 'tool',
    label: '工具',
    children: [
      { id: 'tool:read', label: '查看工具' },
      { id: 'tool:write', label: '配置工具' },
      { id: 'tool:execute', label: '执行工具' },
    ],
  },
  {
    id: 'knowledge',
    label: '知识库',
    children: [
      { id: 'knowledge:read', label: '查看知识库' },
      { id: 'knowledge:write', label: '文件/分段治理' },
    ],
  },
  {
    id: 'platform',
    label: '平台配置',
    children: [
      { id: 'platform:model', label: '模型服务管理' },
      { id: 'platform:safety', label: '安全防护管理' },
      { id: 'platform:database', label: '数据库管理' },
    ],
  },
  {
    id: 'system',
    label: '系统管理',
    children: [
      { id: 'system:user', label: '用户管理' },
      { id: 'system:role', label: '角色与授权' },
      { id: 'system:audit', label: '审计查看' },
    ],
  },
];

const ALL_PERMISSION_IDS = permissionTree.flatMap((group) => group.children.map((item) => item.id));

async function loadUsers(): Promise<void> {
  if (!isAdmin.value) return;
  usersLoading.value = true;
  usersError.value = '';
  try {
    const result = await listPlatformUsers(
      authContext(),
      userPage.value,
      userPageSize.value,
      userSearch.value.trim() || undefined,
    );
    users.value = result.items;
    userTotal.value = result.total;
  } catch (error) {
    users.value = [];
    userTotal.value = 0;
    usersError.value = error instanceof Error ? error.message : '未知错误';
  } finally {
    usersLoading.value = false;
  }
}

async function loadRoles(): Promise<void> {
  if (!isAdmin.value) return;
  rolesLoading.value = true;
  rolesError.value = '';
  try {
    roles.value = await listPlatformRoles(authContext());
  } catch (error) {
    roles.value = [];
    rolesError.value = error instanceof Error ? error.message : '未知错误';
  } finally {
    rolesLoading.value = false;
  }
}

async function loadResourceOptions(): Promise<void> {
  if (!isAdmin.value) return;
  const entries: Array<[string, PlatformAssetType]> = [
    ['agents', 'agents'],
    ['knowledgeBases', 'knowledge-bases'],
    ['tools', 'tools'],
    ['workflows', 'workflows'],
  ];
  await Promise.all(
    entries.map(async ([key, type]) => {
      try {
        const page = await listPlatformAssets(type, authContext(), 1, 100);
        resourceOptions.value[key] = page.items;
      } catch {
        resourceOptions.value[key] = [];
      }
    }),
  );
}

function openEditUser(user: PlatformUser): void {
  editingUser.value = user;
  editUserRoles.value = [...(user.roles ?? [])];
  editUserDisplayName.value = user.displayName ?? '';
  editUserVisible.value = true;
}

async function saveUser(): Promise<void> {
  if (!editingUser.value?.id) return;
  editUserSaving.value = true;
  try {
    await updatePlatformUser(
      editingUser.value.id,
      {
        roles: editUserRoles.value,
        displayName: editUserDisplayName.value.trim() || undefined,
      },
      authContext(),
    );
    ElMessage.success('用户信息已保存');
    editUserVisible.value = false;
    await loadUsers();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '用户保存失败');
  } finally {
    editUserSaving.value = false;
  }
}

async function toggleUser(user: PlatformUser, enable: boolean): Promise<void> {
  const action = enable ? '启用' : '禁用';
  try {
    await ElMessageBox.confirm(
      enable
        ? '确定启用该用户账号？'
        : '禁用后用户将无法登录，在线 token 处理策略由后端执行。确定继续？',
      action + '用户',
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await updatePlatformUser(user.id, { status: enable ? 'ENABLED' : 'DISABLED' }, authContext());
    ElMessage.success('已' + action);
    await loadUsers();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : action + '失败');
  }
}

function openCreateRole(): void {
  editingRole.value = null;
  roleForm.value = { name: '', code: '', description: '' };
  roleResources.value = { agents: [], knowledgeBases: [], tools: [], workflows: [] };
  editRoleVisible.value = true;
  void nextTick(() => permissionTreeRef.value?.setCheckedKeys([]));
}

function openEditRole(role: PlatformRole): void {
  editingRole.value = role;
  roleForm.value = {
    name: role.name,
    code: role.code ?? '',
    description: role.description ?? '',
  };
  roleResources.value = { agents: [], knowledgeBases: [], tools: [], workflows: [] };
  editRoleVisible.value = true;
  void nextTick(() => {
    permissionTreeRef.value?.setCheckedKeys(
      (role.permissions ?? []).filter((item) => ALL_PERMISSION_IDS.includes(item)),
    );
  });
}

async function saveRole(): Promise<void> {
  if (!roleForm.value.name.trim() || (!editingRole.value && !roleForm.value.code.trim())) {
    ElMessage.warning('角色名称与编码均为必填');
    return;
  }
  const checked = (permissionTreeRef.value?.getCheckedKeys(false) as string[]) ?? [];
  const halfChecked = (permissionTreeRef.value?.getHalfCheckedKeys() as string[]) ?? [];
  // 只保存叶子权限，父级由权限树推导
  const permissions = checked
    .filter((item) => ALL_PERMISSION_IDS.includes(item))
    .concat(halfChecked.filter(() => false));
  editRoleSaving.value = true;
  try {
    const body = {
      name: roleForm.value.name.trim(),
      code: roleForm.value.code.trim() || undefined,
      description: roleForm.value.description.trim() || undefined,
      permissions,
    };
    if (editingRole.value?.id) {
      await updatePlatformRole(editingRole.value.id, body, authContext());
    } else {
      await createPlatformRole(body, authContext());
    }
    ElMessage.success('角色已保存');
    editRoleVisible.value = false;
    await loadRoles();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '角色保存失败');
  } finally {
    editRoleSaving.value = false;
  }
}

async function removeRole(role: PlatformRole): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '确定删除角色「' + role.name + '」？仍绑定该角色的用户将按后端策略处理，操作将写入审计日志。',
      '删除角色',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  try {
    await deletePlatformRole(role.id, authContext());
    ElMessage.success('已删除');
    await loadRoles();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除失败');
  }
}

onMounted(() => {
  void loadUsers();
  void loadRoles();
  void loadResourceOptions();
});
</script>

<style scoped>
.system-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 14px;
}

.system-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.system-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}

.system-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.tab-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.search-input {
  width: 240px;
}
.toolbar-title {
  color: var(--ui-muted);
  font-size: 13px;
}
.gap-block {
  margin-bottom: 4px;
}
.tab-pagination {
  justify-content: flex-end;
}
.role-tag {
  margin-right: 4px;
}
.full-width {
  width: 100%;
}
.form-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}
.form-row .grow {
  flex: 1;
  min-width: 200px;
}
.permission-tree {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid var(--ui-border);
  border-radius: 8px;
  padding: 8px;
}
.resource-collapse {
  border-top: 1px solid var(--ui-border);
}
</style>
